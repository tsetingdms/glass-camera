"""Fit the Glass Camera colour looks (app/src/main/assets/looks/*.png) from before/after slider screenshots.

Usage: python tools/fit_luts.py <screenshot folder> [output folder]
Each look needs two 2000x1194 screenshots of the same frame in a before/after slider: <id>_a.webp with the slider at
the far right (the frame shows "before") and <id>_b.webp with it at the far left ("after"). The reference screenshots
are not kept in the repo. Output: <id>.png (33^3 LUT atlas, see Looks.kt) and <id>_check.jpg (before | fit | after).

Model: a luminance-dependent affine colour transform (per brightness band: 3x3 matrix + offset, ridge-regularised
towards one global affine fit) as a smooth prior that extrapolates safely to colours the screenshots don't contain,
plus data-driven residual corrections splatted onto the 33^3 LUT grid where there is data.
"""
import os
import sys
import numpy as np
from PIL import Image

IMG = sys.argv[1] if len(sys.argv) > 1 else 'reference'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'luts'
os.makedirs(OUT, exist_ok=True)

LOOKS = ['amber', 'pale_green', 'harbour_blue', 'island_cyan', 'avenue_star', 'mong_kok']
N = 33
BANDS = 16
W709 = np.array([0.2126, 0.7152, 0.0722])


def load(name):
    return np.asarray(Image.open(os.path.join(IMG, name + '.webp')).convert('RGB'), np.float64) / 255.0


def samples(a, b):
    # Region where screenshot a shows "before" and b shows "after", away from sliders, labels and the title block.
    y0, y1, x0, x1 = 130, 930, 175, 1825
    X = a[y0:y1:2, x0:x1:2].reshape(-1, 3)
    Y = b[y0:y1:2, x0:x1:2].reshape(-1, 3)
    yy, xx = np.mgrid[y0:y1:2, x0:x1:2]
    held = (((yy // 48) + (xx // 48)) % 5 == 0).reshape(-1)  # validation blocks
    return X, Y, held


def design(X):
    return np.hstack([X, np.ones((len(X), 1))])


def fit_prior(X, Y):
    D = design(X)
    G = np.linalg.lstsq(D, Y, rcond=None)[0]  # global affine, 4x3
    L = X @ W709
    centers = (np.arange(BANDS) + 0.5) / BANDS
    mats = []
    for c in centers:
        w = np.clip(1 - np.abs(L - c) * BANDS, 0, 1)  # tent weights around the band centre
        mu = 400.0
        Dw = D * w[:, None]
        A = D.T @ Dw + mu * np.eye(4)
        B = D.T @ (Y * w[:, None]) + mu * G
        mats.append(np.linalg.solve(A, B))
    return np.array(mats), centers


def apply_prior(mats, centers, X):
    L = np.clip(X @ W709, 0, 1)
    t = np.clip(L * BANDS - 0.5, 0, BANDS - 1)
    i0 = np.floor(t).astype(int)
    i1 = np.minimum(i0 + 1, BANDS - 1)
    f = (t - i0)[:, None]
    D = design(X)
    y0 = np.einsum('nk,nkc->nc', D, mats[i0])
    y1 = np.einsum('nk,nkc->nc', D, mats[i1])
    return y0 * (1 - f) + y1 * f


def chroma_limits(X):
    """95th percentile of chroma (distance from grey) per brightness band: how saturated the screenshots get."""
    L = X @ W709
    c = np.linalg.norm(X - L[:, None], axis=1)
    band = np.clip((L * BANDS).astype(int), 0, BANDS - 1)
    lim = np.array([np.percentile(c[band == k], 95) if (band == k).sum() > 200 else np.nan for k in range(BANDS)])
    fill = np.nanmax(lim) if np.isfinite(lim).any() else 0.2
    return np.maximum(np.where(np.isfinite(lim), lim, fill), 0.05)


def safe_prior(mats, centers, X, limits):
    """The fitted grade up to the most saturated colours seen; beyond that, extra chroma keeps its hue (no runaway
    hue rotation on neon signs or bright clothes) and only gets the grade's saturation gain."""
    L = np.clip(X @ W709, 0, 1)
    c = X - L[:, None]
    norm = np.linalg.norm(c, axis=1)
    lim = limits[np.clip((L * BANDS).astype(int), 0, BANDS - 1)]
    scale = np.minimum(1.0, lim / np.maximum(norm, 1e-6))
    inside = L[:, None] + c * scale[:, None]
    y = apply_prior(mats, centers, inside)
    yl = y @ W709
    gain = np.linalg.norm(y - yl[:, None], axis=1) / np.maximum(np.linalg.norm(inside - L[:, None], axis=1), 1e-6)
    gain = np.clip(gain, 0.7, 1.3)
    return y + (c - c * scale[:, None]) * gain[:, None]


def build_lut(X, Y, mats, centers, lam=30.0):
    g = np.linspace(0, 1, N)
    R, Gc, B = np.meshgrid(g, g, g, indexing='ij')  # [r][g][b]
    nodes = np.stack([R, Gc, B], -1).reshape(-1, 3)
    prior = safe_prior(mats, centers, nodes, chroma_limits(X)).reshape(N, N, N, 3)
    resid = Y - apply_prior(mats, centers, X)
    acc = np.zeros((N, N, N, 3))
    wsum = np.zeros((N, N, N))
    p = np.clip(X, 0, 1) * (N - 1)
    i0 = np.minimum(np.floor(p).astype(int), N - 2)
    f = p - i0
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 2] if db else 1 - f[:, 2])
                idx = (i0[:, 0] + dr, i0[:, 1] + dg, i0[:, 2] + db)
                np.add.at(wsum, idx, w)
                np.add.at(acc, idx, resid * w[:, None])
    corr = acc / (wsum + lam)[..., None]
    # Light smoothing of the correction field (keeps the grade free of blotches).
    for axis in range(3):
        corr = (np.roll(corr, 1, axis) + 2 * corr + np.roll(corr, -1, axis)) / 4
    return np.clip(prior + corr, 0, 1)


def apply_lut(lut, X):
    p = np.clip(X, 0, 1) * (N - 1)
    i0 = np.minimum(np.floor(p).astype(int), N - 2)
    f = p - i0
    out = np.zeros_like(X)
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = (f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 2] if db else 1 - f[:, 2])
                out += lut[i0[:, 0] + dr, i0[:, 1] + dg, i0[:, 2] + db] * w[:, None]
    return out


def save_atlas(lut, path):
    # Atlas: width N*N (blue slices side by side, red across each slice), height N (green).
    img = np.zeros((N, N * N, 3))
    for b in range(N):
        img[:, b * N:(b + 1) * N, :] = lut[:, :, b, :].transpose(1, 0, 2)  # rows = g, cols = r
    Image.fromarray(np.round(img * 255).astype(np.uint8)).save(path, optimize=True)


def main():
    report = []
    for name in LOOKS:
        a, b = load(name + '_a'), load(name + '_b')
        X, Y, held = samples(a, b)
        train = ~held
        mats, centers = fit_prior(X[train], Y[train])
        lut = build_lut(X[train], Y[train], mats, centers)
        err_id = np.abs(X[held] - Y[held]).mean() * 255
        err_prior = np.abs(apply_prior(mats, centers, X[held]) - Y[held]).mean() * 255
        err_lut = np.abs(apply_lut(lut, X[held]) - Y[held]).mean() * 255
        # Final LUT from all data.
        mats, centers = fit_prior(X, Y)
        lut = build_lut(X, Y, mats, centers)
        save_atlas(lut, os.path.join(OUT, name + '.png'))
        # Preview: before | our LUT | their after (whole frame).
        h, w, _ = a.shape
        ours = apply_lut(lut, a.reshape(-1, 3)).reshape(h, w, 3)
        strip = np.concatenate([a[130:930, 175:1825], ours[130:930, 175:1825], b[130:930, 175:1825]], 1)
        Image.fromarray(np.round(np.clip(strip, 0, 1) * 255).astype(np.uint8)).resize((strip.shape[1] // 3, strip.shape[0] // 3)).save(os.path.join(OUT, name + '_check.jpg'), quality=88)
        report.append('%-13s held-out error (0-255): no grade %.1f | smooth model %.1f | final LUT %.1f' % (name, err_id, err_prior, err_lut))
    print('\n'.join(report))


main()

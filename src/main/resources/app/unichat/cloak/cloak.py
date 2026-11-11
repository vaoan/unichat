# Unichat's image protection against AI training: the textural attack of Mist (https://github.com/psyker-team/mist,
# "Mist: Towards Improved Adversarial Examples for Diffusion Models") and PhotoGuard's encoder attack, written anew.
# A small, imperceptible-as-possible change moves what Stable Diffusion's image encoder sees toward a target pattern,
# so models trained on, or editing from, the picture get the pattern instead of it.
#
# What keeps it hard to see: each pixel may change only as much as its neighbourhood's texture hides (smooth skin and
# sky barely move), the change is mostly in brightness (coloured specks show most), it starts from the picture itself
# (Mist's random start was most of the visible noise at few steps), and the tile grid moves half a tile every other
# step, so no tile edge stays put long enough to show. The change is worked out at up to 768 px and then laid,
# enlarged, over the photo at its own size (up to MAX_OUTPUT): models shrink pictures before using them, which brings it back.
#
# Only the encoder is loaded (34M parameters), and the picture goes in tiles of at most 256 px: memory stays near
# 1.5 GB whatever the size. Prints "progress <done> <total>" (in pixels) after each tile, then "done".
#
# python cloak.py --weights vae.safetensors --target target.png --input in.png --output out.png --steps 30
import argparse
import math
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image, ImageOps
from safetensors.torch import load_file

TILE = 256
MAX_MEMORY = 4 * 1024 ** 3  # never more than this: it stops instead of taking the computer's memory (it needs ~1.5 GB)
EPS = 12 / 255 * 2   # the largest change per channel, in the [-1, 1] range, where the texture hides it best
SMOOTH_SHARE = 0.15  # what the smoothest areas may take of it
TEXTURE = 10         # local spread of brightness (0..255) that hides the whole of EPS
COLOUR = 0.3         # the change in colour, against the one in brightness
STEP = 1 / 255 * 2
MAX_OUTPUT = 4096    # the long side sent at most: a bigger PNG would pass the server's 100 MB upload limit


def norm(c):
    return nn.GroupNorm(32, c, eps=1e-6)


class Resnet(nn.Module):
    def __init__(self, cin, cout):
        super().__init__()
        self.norm1, self.conv1 = norm(cin), nn.Conv2d(cin, cout, 3, padding=1)
        self.norm2, self.conv2 = norm(cout), nn.Conv2d(cout, cout, 3, padding=1)
        self.conv_shortcut = nn.Conv2d(cin, cout, 1) if cin != cout else None

    def forward(self, x):
        h = self.conv1(F.silu(self.norm1(x)))
        h = self.conv2(F.silu(self.norm2(h)))
        return (x if self.conv_shortcut is None else self.conv_shortcut(x)) + h


class Downsample(nn.Module):
    def __init__(self, c):
        super().__init__()
        self.conv = nn.Conv2d(c, c, 3, stride=2)

    def forward(self, x):
        return self.conv(F.pad(x, (0, 1, 0, 1)))


class Attention(nn.Module):
    def __init__(self, c):
        super().__init__()
        self.group_norm = norm(c)
        self.to_q, self.to_k, self.to_v = nn.Linear(c, c), nn.Linear(c, c), nn.Linear(c, c)
        self.to_out = nn.ModuleList([nn.Linear(c, c)])

    def forward(self, x):
        b, c, h, w = x.shape
        t = self.group_norm(x).view(b, c, h * w).transpose(1, 2)
        a = F.scaled_dot_product_attention(self.to_q(t)[:, None], self.to_k(t)[:, None], self.to_v(t)[:, None])[:, 0]
        return x + self.to_out[0](a).transpose(1, 2).view(b, c, h, w)


class Block(nn.Module):
    def __init__(self, cin, cout, down):
        super().__init__()
        self.resnets = nn.ModuleList([Resnet(cin, cout), Resnet(cout, cout)])
        self.downsamplers = nn.ModuleList([Downsample(cout)]) if down else None

    def forward(self, x):
        for r in self.resnets:
            x = r(x)
        return x if self.downsamplers is None else self.downsamplers[0](x)


class Mid(nn.Module):
    def __init__(self, c):
        super().__init__()
        self.resnets = nn.ModuleList([Resnet(c, c), Resnet(c, c)])
        self.attentions = nn.ModuleList([Attention(c)])

    def forward(self, x):
        return self.resnets[1](self.attentions[0](self.resnets[0](x)))


class Encoder(nn.Module):
    """Stable Diffusion 1.x's image encoder, named as in the diffusers weights."""

    def __init__(self):
        super().__init__()
        ch = [128, 256, 512, 512]
        self.conv_in = nn.Conv2d(3, 128, 3, padding=1)
        self.down_blocks = nn.ModuleList([Block(ch[max(i - 1, 0)], ch[i], i < 3) for i in range(4)])
        self.mid_block = Mid(512)
        self.conv_norm_out = norm(512)
        self.conv_out = nn.Conv2d(512, 8, 3, padding=1)

    def forward(self, x):
        x = self.conv_in(x)
        for b in self.down_blocks:
            x = b(x)
        return self.conv_out(F.silu(self.conv_norm_out(self.mid_block(x))))


class Vae(nn.Module):
    def __init__(self):
        super().__init__()
        self.encoder = Encoder()
        self.quant_conv = nn.Conv2d(8, 8, 1)

    def latent(self, x):
        """A sample of the latent, as Stable Diffusion takes it: the noise makes the change hold for any sample."""
        mean, logvar = self.quant_conv(self.encoder(x)).chunk(2, dim=1)
        return mean + torch.exp(0.5 * logvar.clamp(-30, 20)) * torch.randn_like(mean)


def tensor(img):
    """As [-1, 1] floats, 1×3×h×w."""
    return torch.from_numpy(np.asarray(img, dtype=np.float32) / 127.5 - 1).permute(2, 0, 1)[None]


def working_size(w, h, max_side):
    """At most max_side on the long side (never larger than it was), multiples of 8 as the encoder needs."""
    s = min(1.0, max_side / max(w, h))
    return max(8, int(w * s) // 8 * 8), max(8, int(h * s) // 8 * 8)


def cuts(n):
    """Tile edges along one side: as even as possible, each a multiple of 8 and at most TILE."""
    k = math.ceil(n / TILE)
    step = math.ceil(n / k / 8) * 8
    return [(a, min(a + step, n)) for a in range(0, n, step)]


def shifted(n):
    """The same tiles moved half a tile: each edge of cuts(n) falls in the middle of one of these."""
    if n <= TILE:
        return cuts(n)
    edges = sorted({0, n, *((a + b) // 16 * 8 for a, b in cuts(n))})
    return list(zip(edges, edges[1:]))


def blur(x, sigma):
    """Gaussian, channel by channel, the edges repeated."""
    r = max(1, int(3 * sigma))
    g = torch.exp(-torch.arange(-r, r + 1, dtype=torch.float32) ** 2 / (2 * sigma ** 2))
    g = g / g.sum()
    c = x.shape[1]
    x = F.conv2d(F.pad(x, (r, r, 0, 0), mode="replicate"), g.view(1, 1, 1, -1).repeat(c, 1, 1, 1), groups=c)
    return F.conv2d(F.pad(x, (0, 0, r, r), mode="replicate"), g.view(1, 1, -1, 1).repeat(c, 1, 1, 1), groups=c)


def budget(x):
    """How much each pixel may change: EPS where the brightness around it varies by TEXTURE or more, less where smooth."""
    luma = (0.299 * x[:, 0:1] + 0.587 * x[:, 1:2] + 0.114 * x[:, 2:3]) * 127.5
    mean = blur(luma, 2)
    spread = (blur(luma * luma, 2) - mean * mean).clamp_min(0).sqrt()
    return blur((spread / TEXTURE).clamp(SMOOTH_SHARE, 1), 2) * EPS


def peak_memory():
    """The most this process has held, in bytes, or 0 when the system doesn't say."""
    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes

        class Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
                (n, ctypes.c_size_t) for n in ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                                               "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                                               "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]

        c = Counters()
        c.cb = ctypes.sizeof(c)
        get = ctypes.WinDLL("psapi").GetProcessMemoryInfo
        get.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        ok = get(ctypes.WinDLL("kernel32").GetCurrentProcess(), ctypes.byref(c), c.cb)
        return max(c.PeakWorkingSetSize, c.PeakPagefileUsage) if ok else 0
    import resource
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return peak if sys.platform == "darwin" else peak * 1024  # macOS says bytes, Linux KB


def check_memory():
    peak = peak_memory()
    if peak > MAX_MEMORY:
        raise MemoryError(f"used {peak // 1024 ** 2} MB, more than {MAX_MEMORY // 1024 ** 2} MB")


def protect(vae, x, target, steps, tell):
    """The change to x (same shape) that moves its tiles' latents toward the target's."""
    limit = budget(x)
    grids = [[(y, x0) for y in tiles(x.shape[2]) for x0 in tiles(x.shape[3])] for tiles in (cuts, shifted)]
    with torch.no_grad():
        goals = [[vae.latent(target[..., y0:y1, x0:x1]) for (y0, y1), (x0, x1) in g] for g in grids]
    delta = torch.zeros_like(x)
    for i in range(steps):
        grad = torch.zeros_like(x)
        for ((y0, y1), (x0, x1)), goal in zip(grids[i % 2], goals[i % 2]):
            d = delta[..., y0:y1, x0:x1].clone().requires_grad_()
            loss = F.mse_loss(vae.latent(x[..., y0:y1, x0:x1] + d), goal, reduction="sum")
            grad[..., y0:y1, x0:x1], = torch.autograd.grad(loss, d)
            check_memory()
            tell((y1 - y0) * (x1 - x0))
        with torch.no_grad():
            grey = grad.mean(1, keepdim=True)
            delta -= STEP * (grey + COLOUR * (grad - grey)).sign()
            delta = torch.maximum(torch.minimum(delta, limit), -limit)
            delta = (x + delta).clamp(-1, 1) - x
    return delta


def enlarged(delta, size):
    """The change, h×w×3 in 0..255 units, at the photo's size."""
    channels = []
    for c in delta[0].numpy() * 127.5:
        d = Image.fromarray(c)
        channels.append(np.asarray(d.resize(size, Image.BICUBIC) if d.size != size else d))
    return np.stack(channels, axis=-1)


def main():
    p = argparse.ArgumentParser()
    for a in ("--weights", "--target", "--input", "--output"):
        p.add_argument(a, required=True)
    p.add_argument("--steps", type=int, required=True)
    p.add_argument("--max-side", type=int, default=768)
    p.add_argument("--threads", type=int, default=0)
    a = p.parse_args()
    if a.threads > 0:
        torch.set_num_threads(a.threads)
    torch.manual_seed(0)

    vae = Vae()
    weights = {k: v.float() for k, v in load_file(a.weights).items() if k.startswith(("encoder.", "quant_conv."))}
    vae.load_state_dict(weights)
    vae.eval().requires_grad_(False)

    with Image.open(a.input) as im:
        photo = ImageOps.exif_transpose(im).convert("RGB")  # as it's shown: the PNG keeps no orientation tag
    if max(photo.size) > MAX_OUTPUT:
        s = MAX_OUTPUT / max(photo.size)
        photo = photo.resize((round(photo.width * s), round(photo.height * s)), Image.LANCZOS)
    size = working_size(*photo.size, a.max_side)
    x = tensor(photo.resize(size, Image.BICUBIC) if size != photo.size else photo)
    with Image.open(a.target) as t:
        target = tensor(t.convert("RGB").resize(size, Image.BICUBIC))
    total, done = size[0] * size[1] * a.steps, [0]

    def tell(pixels):
        done[0] += pixels
        print(f"progress {done[0]} {total}", flush=True)

    delta = protect(vae, x, target, a.steps, tell)
    out = np.asarray(photo, dtype=np.float32) + enlarged(delta, photo.size)
    Image.fromarray(out.round().clip(0, 255).astype(np.uint8)).save(a.output, format="PNG")
    print("done", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # one line Unichat can log
        print(f"error {type(e).__name__}: {e}", file=sys.stderr, flush=True)
        sys.exit(1)

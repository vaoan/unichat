# Unichat's image protection against AI training: the textural attack of Mist (https://github.com/psyker-team/mist,
# "Mist: Towards Improved Adversarial Examples for Diffusion Models") and PhotoGuard's encoder attack, written anew.
# A small, imperceptible-as-possible change moves what Stable Diffusion's image encoder sees toward a target pattern,
# so models trained on, or editing from, the picture get the pattern instead of it.
#
# Only the encoder is loaded (34M parameters), and the picture goes in tiles of at most 256 px: memory stays near
# 1 GB whatever the size. Prints "progress <done> <total>" after each step, then "done".
#
# python cloak.py --weights vae.safetensors --target target.png --input in.png --output out.png --steps 30
import argparse
import math
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image
from safetensors.torch import load_file

TILE = 256
MAX_MEMORY = 4 * 1024 ** 3  # never more than this: it stops instead of taking the computer's memory (it needs ~1.5 GB)
EPS = 16 / 255 * 2   # the largest change per channel, in the [-1, 1] range (Mist's default strength)
STEP = 1 / 255 * 2


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


def load(path, size):
    """As [-1, 1] floats, 1×3×h×w."""
    img = Image.open(path).convert("RGB").resize(size, Image.BICUBIC)
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
    delta = (torch.rand_like(x) * 2 - 1) * EPS  # a random start, as Mist's
    delta = ((x + delta).clamp(-1, 1) - x).requires_grad_()
    with torch.no_grad():
        goal = vae.latent(target)
    for _ in range(steps):
        loss = F.mse_loss(vae.latent(x + delta), goal, reduction="sum")
        grad, = torch.autograd.grad(loss, delta)
        with torch.no_grad():
            delta -= STEP * grad.sign()
            delta.clamp_(-EPS, EPS)
            delta.copy_((x + delta).clamp(-1, 1) - x)
        check_memory()
        tell()
    return (x + delta).detach()


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
        size = working_size(*im.size, a.max_side)
    x, target = load(a.input, size), load(a.target, size)
    tiles = [(y, x0) for y in cuts(size[1]) for x0 in cuts(size[0])]
    total, done = len(tiles) * a.steps, [0]

    def tell():
        done[0] += 1
        print(f"progress {done[0]} {total}", flush=True)

    out = x.clone()
    for (y0, y1), (x0, x1) in tiles:
        out[..., y0:y1, x0:x1] = protect(vae, x[..., y0:y1, x0:x1], target[..., y0:y1, x0:x1], a.steps, tell)
    pixels = ((out[0].permute(1, 2, 0).numpy() + 1) * 127.5).round().clip(0, 255).astype(np.uint8)
    Image.fromarray(pixels).save(a.output, format="PNG")
    print("done", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # one line Unichat can log
        print(f"error {type(e).__name__}: {e}", file=sys.stderr, flush=True)
        sys.exit(1)

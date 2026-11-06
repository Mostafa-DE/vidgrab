import cairosvg, io
from PIL import Image, ImageDraw
P = [("M52,26 L52,66", None), ("M52,26 C53,31 63,32 64,41", None), ("M39,54 L52,67 L65,54", None), ("M33,79 L71,79", None)]
fg = '<g transform="translate(54,54) scale(0.74) translate(-52,-52.5)">' + ''.join(f'<path d="{d}" fill="none" stroke="#FFFFFF" stroke-width="8.5" stroke-linecap="round" stroke-linejoin="round"/>' for d,_ in P) + '</g>'
bg = '<defs><linearGradient id="g" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#5B21D6"/><stop offset="1" stop-color="#E8237F"/></linearGradient></defs><rect width="108" height="108" fill="url(#g)"/>'
svg = lambda body: f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">{body}</svg>'
def png(body, s=512): return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg(body).encode(), output_width=s, output_height=s))).convert("RGBA")
full = png(bg + fg); mono = png('<rect width="108" height="108" fill="#E8DEF8"/>' + fg.replace('#FFFFFF', '#3B2A60'))
# masks show the inner 72/108 visible area like a launcher does
def masked(img, kind):
    c = img.crop((85, 85, 427, 427)).resize((240, 240), Image.LANCZOS); m = Image.new("L", (240, 240), 0); d = ImageDraw.Draw(m)
    d.ellipse((0,0,239,239), fill=255) if kind == "circle" else d.rounded_rectangle((0,0,239,239), radius=80, fill=255)
    out = Image.new("RGBA", (240, 240), (0, 0, 0, 0)); out.paste(c, (0, 0), m); return out
canvas = Image.new("RGBA", (1100, 560), (245, 243, 250, 255)); d = ImageDraw.Draw(canvas)
canvas.paste(full.resize((240, 240)), (30, 40)); canvas.alpha_composite(masked(full, "circle"), (300, 40))
canvas.alpha_composite(masked(full, "squircle"), (570, 40)); canvas.alpha_composite(masked(mono, "circle"), (840, 40))
for x, t in [(30, "full layers"), (300, "circle mask"), (570, "squircle mask"), (840, "themed (mono)")]: d.text((x + 70, 290), t, fill=(60, 60, 60))
# small sizes legibility
for i, s in enumerate([96, 64, 48, 32]):
    canvas.alpha_composite(masked(full, "circle").resize((s, s), Image.LANCZOS), (300 + i * 130, 340))
d.text((30, 380), "small sizes ->", fill=(60, 60, 60))
canvas.convert("RGB").save("/workspace/vidgrab/icon-preview.png")
masked(full, "squircle").resize((512, 512), Image.LANCZOS).save("/workspace/vidgrab/icon-512.png")

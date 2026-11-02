The London Trainer mark uses an ivory knight, an emerald background, and gold accents. Android uses adaptive vector layers with a monochrome version for themed icons. The Play asset is a full-bleed 512px RGBA PNG.

Regenerate the vectors, SVG source, preview, and Play asset with:

```sh
python3 -m venv /tmp/london-icon-venv
/tmp/london-icon-venv/bin/pip install cairosvg
/tmp/london-icon-venv/bin/python -I tools/gen_app_icon.py
```

The generator is the source of truth for geometry and palette.

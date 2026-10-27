import os, io, contextlib, traceback, json
T = os.environ.get("HF_TOKEN", "")
os.makedirs("out/probe", exist_ok=True)
def note(t, m): print(f"::notice title={t}::{str(m)[:900]}")
from gradio_client import Client
spaces = ["tencent/Hunyuan3D-2.1", "trellis-community/TRELLIS", "microsoft/TRELLIS.2", "JeffreyXiang/TRELLIS",
          "stabilityai/stable-fast-3d", "tencent/Hunyuan3D-2mini-Turbo", "VAST-AI/TripoSG", "tencent/Hunyuan3D-2",
          "black-forest-labs/FLUX.1-schnell"]
for s in spaces:
    try:
        c = Client(s, token=T or None)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf): c.view_api(all_endpoints=False)
        txt = buf.getvalue()
        open("out/probe/" + s.replace("/", "__") + ".txt", "w").write(txt)
        names = [l.strip() for l in txt.splitlines() if "api_name=" in l]
        note("OK " + s, " | ".join(names)[:800])
    except Exception as e:
        note("FAIL " + s, repr(e)[:400])

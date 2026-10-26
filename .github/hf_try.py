import os, io, contextlib, time, traceback
T=os.environ.get("HF_TOKEN",""); S=os.environ["SPACE"]; M=os.environ["MODE"]
os.makedirs("out", exist_ok=True)
def note(t, m): print(f"::notice title={t}::{str(m)[:900]}")
try:
    from gradio_client import Client, handle_file
    c = Client(S, token=T or None)
    if M == "api":
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf): c.view_api(all_endpoints=True)
        open("out/api.txt","w").write(buf.getvalue())
        note("api", buf.getvalue()[:900])
    else:
        exec(open(".github/hf_gen.py").read())
except Exception:
    tb = traceback.format_exc()
    open("out/err.txt","w").write(tb)
    note("err", tb[-900:])

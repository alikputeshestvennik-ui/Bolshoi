import json, shutil, time
def path_of(x):
    if isinstance(x, dict): return x.get("value") or x.get("path") or x.get("name")
    return x
t0 = time.time()
import urllib.request
tree = json.load(urllib.request.urlopen("https://huggingface.co/api/spaces/tencent/Hunyuan3D-2/tree/main/assets/example_images"))
imgs = sorted(x["path"] for x in tree if x["path"].lower().endswith((".png", ".jpg", ".jpeg", ".webp")))
note("examples", ", ".join(imgs[:40]))
pick = os.environ.get("PICK", "")
chosen = next((p for p in imgs if pick and pick in p), imgs[len(imgs)//3] if imgs else None)
src = "out/input" + chosen[chosen.rfind("."):]
urllib.request.urlretrieve("https://huggingface.co/spaces/tencent/Hunyuan3D-2/resolve/main/" + chosen, src)
note("input", chosen)
textured = True
try:
    r = c.predict(None, handle_file(src), None, None, None, None, 30, 5.0, 1234, 256, True, 8000, False, api_name="/generation_all")
    white, tex = path_of(r[0]), path_of(r[1]); stats = r[3]
except Exception as ex:
    note("tex_fail", repr(ex)[:300])
    textured = False
    r = c.predict(None, handle_file(src), None, None, None, None, 30, 5.0, 1234, 256, True, 8000, False, api_name="/shape_generation")
    white = path_of(r[0]); tex = white; stats = r[2]
t1 = time.time()
open("out/gen_result.json", "w").write(json.dumps([str(x)[:400] for x in r], ensure_ascii=False, indent=1))
shutil.copy(white, "out/white_mesh" + (white[white.rfind("."):] if "." in white else ".glb"))
shutil.copy(tex, "out/textured" + (tex[tex.rfind("."):] if "." in tex else ".glb"))
e = c.predict(handle_file(white), handle_file(tex), "obj", True, textured, 30000, api_name="/on_export_click")
t2 = time.time()
d = path_of(e[1])
shutil.copy(d, "out/export" + (d[d.rfind("."):] if "." in d else ".obj"))
open("out/time.txt", "w").write(f"generation {t1-t0:.0f}s, export {t2-t1:.0f}s; textured={textured}; stats: {str(stats)[:600]}")
note("done", open("out/time.txt").read())

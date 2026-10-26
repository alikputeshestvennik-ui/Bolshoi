import json, shutil, time
def path_of(x):
    if isinstance(x, dict): return x.get("value") or x.get("path") or x.get("name")
    return x
t0 = time.time()
src = "agent_android/app/src/main/res/drawable-nodpi/ic_launcher_art.png"
r = c.predict(None, handle_file(src), None, None, None, None, 30, 5.0, 1234, 256, True, 8000, False, api_name="/generation_all")
t1 = time.time()
open("out/gen_result.json", "w").write(json.dumps([str(x)[:400] for x in r], ensure_ascii=False, indent=1))
white, tex = path_of(r[0]), path_of(r[1])
shutil.copy(white, "out/white_mesh" + (white[white.rfind("."):] if "." in white else ".glb"))
shutil.copy(tex, "out/textured" + (tex[tex.rfind("."):] if "." in tex else ".glb"))
e = c.predict(handle_file(white), handle_file(tex), "obj", True, True, 30000, api_name="/on_export_click")
t2 = time.time()
d = path_of(e[1])
shutil.copy(d, "out/export" + (d[d.rfind("."):] if "." in d else ".obj"))
open("out/time.txt", "w").write(f"generation {t1-t0:.0f}s, export {t2-t1:.0f}s; stats: {str(r[3])[:600]}")
note("done", open("out/time.txt").read())

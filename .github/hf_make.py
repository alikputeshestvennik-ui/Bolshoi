import os, json, time, shutil, traceback, urllib.request
from gradio_client import Client, handle_file
T = os.environ.get("HF_TOKEN", "") or None
P = os.environ.get("PROMPT", "").strip() or "cute cartoon red fox toy, full body, standing"
ONLY = os.environ.get("SPACE", "").strip()
os.makedirs("out/make", exist_ok=True)
def note(t, m): print(f"::notice title={t}::{str(m)[:900]}")
def pth(x):
    if isinstance(x, (list, tuple)): x = x[0]
    if isinstance(x, dict): return x.get("value") or x.get("path") or x.get("name") or x.get("video")
    return x
res = {}
# 1) картинка: ссылка или текст → FLUX
src = "out/make/input.png"
t0 = time.time()
if P.startswith("http"):
    urllib.request.urlretrieve(P, src); note("input", "downloaded " + P[:200])
else:
    c = Client("black-forest-labs/FLUX.1-schnell", token=T)
    r = c.predict(P + ", isolated on plain white background, centered, single object, 3D render, soft studio light",
                  0, True, 1024, 1024, 4, api_name="/infer")
    shutil.copy(pth(r), src); note("flux", f"{time.time()-t0:.0f}s")
def run(name, fn):
    if ONLY and ONLY not in name: return
    t = time.time()
    try:
        out = fn()
        dst = f"out/make/{name.replace('/', '__')}.glb"; shutil.copy(out, dst)
        res[name] = {"ok": True, "sec": round(time.time() - t), "kb": os.path.getsize(dst) // 1024}
    except Exception as e:
        res[name] = {"ok": False, "sec": round(time.time() - t), "err": repr(e)[:300]}
    note(name, json.dumps(res[name], ensure_ascii=False))
def trellis2():
    c = Client("microsoft/TRELLIS.2", token=T)
    try: c.predict(api_name="/start_session")
    except Exception: pass
    img = c.predict(handle_file(src), api_name="/preprocess_image")
    c.predict(image=handle_file(pth(img)), api_name="/image_to_3d")
    r = c.predict(api_name="/extract_glb") if False else c.predict(decimation_target=200000, texture_size=2048, api_name="/extract_glb")
    return pth(r[1]) or pth(r[0])
def trellis():
    c = Client("trellis-community/TRELLIS", token=T)
    try: c.predict(api_name="/start_session")
    except Exception: pass
    img = c.predict(handle_file(src), api_name="/preprocess_image")
    r = c.predict(image=handle_file(pth(img)), multiimages=[], api_name="/generate_and_extract_glb")
    return pth(r[2]) or pth(r[1])
def hunyuan21():
    c = Client("tencent/Hunyuan3D-2.1", token=T)
    r = c.predict(image=handle_file(src), api_name="/generation_all")
    return pth(r[1])
def sf3d():
    c = Client("stabilityai/stable-fast-3d", token=T)
    r = c.predict(input_image=handle_file(src), foreground_ratio=0.85, remesh_option="None", vertex_count=-1, texture_size=1024, api_name="/run_button")
    return pth(r[1])
def triposg():
    c = Client("VAST-AI/TripoSG", token=T)
    try: c.predict(api_name="/start_session")
    except Exception: pass
    seg = c.predict(handle_file(src), api_name="/run_segmentation")
    mesh = c.predict(image=handle_file(pth(seg)), seed=0, num_inference_steps=50, guidance_scale=7.0, simplify=True, target_face_num=100000, api_name="/image_to_3d")
    r = c.predict(image=handle_file(src), mesh_path=pth(mesh), seed=0, api_name="/run_texture")
    return pth(r)
for n, f in [("microsoft/TRELLIS.2", trellis2), ("trellis-community/TRELLIS", trellis), ("tencent/Hunyuan3D-2.1", hunyuan21),
             ("stabilityai/stable-fast-3d", sf3d), ("VAST-AI/TripoSG", triposg)]:
    run(n, f)
json.dump({"prompt": P, "results": res}, open("out/make/results.json", "w"), ensure_ascii=False, indent=1)
# превью: рендер каждой модели с цветами текстуры
try:
    import trimesh, numpy as np, matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection
    for f in sorted(os.listdir("out/make")):
        if not f.endswith(".glb"): continue
        try:
            sc = trimesh.load("out/make/" + f, force="scene")
            m = sc.dump(concatenate=True) if hasattr(sc, "dump") else sc
            if len(m.faces) > 40000:
                idx = np.random.default_rng(0).choice(len(m.faces), 40000, replace=False); faces = m.faces[idx]
            else: idx = np.arange(len(m.faces)); faces = m.faces
            try: fc = m.visual.to_color().face_colors[idx] / 255.0
            except Exception: fc = np.tile([0.7, 0.7, 0.7, 1], (len(faces), 1))
            v = m.vertices - m.vertices.mean(0); v /= np.abs(v).max()
            # glTF: Y вверх → в matplotlib Z вверх
            vv = v[:, [0, 2, 1]]; vv[:, 1] *= -1
            fig = plt.figure(figsize=(8, 4), dpi=110)
            for k, az in enumerate([-60, 30]):
                ax = fig.add_subplot(1, 2, k + 1, projection="3d")
                tri = vv[faces]
                # простая подсветка по нормали
                n = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]); n /= (np.linalg.norm(n, axis=1, keepdims=True) + 1e-9)
                shade = 0.55 + 0.45 * np.clip(n @ np.array([0.3, -0.5, 0.8]), 0, 1)
                col = fc.copy(); col[:, :3] *= shade[:, None]
                pc = Poly3DCollection(tri, facecolors=col, linewidths=0); ax.add_collection3d(pc)
                ax.set_xlim(-1, 1); ax.set_ylim(-1, 1); ax.set_zlim(-1, 1); ax.view_init(18, az); ax.axis("off")
            fig.suptitle(f.replace("__", "/").replace(".glb", "")); fig.tight_layout()
            fig.savefig("out/make/" + f.replace(".glb", ".png")); plt.close(fig)
        except Exception as e:
            note("render " + f, repr(e)[:300])
except Exception:
    note("render", traceback.format_exc()[-600:])

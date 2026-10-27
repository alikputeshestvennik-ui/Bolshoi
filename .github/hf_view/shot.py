import os, sys, glob, subprocess, time
from playwright.sync_api import sync_playwright

root = os.getcwd()
srv = subprocess.Popen([sys.executable, "-m", "http.server", "8765"], cwd=root,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(1.5)
try:
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"])
        pg = b.new_page(viewport={"width": 700, "height": 700})
        for f in sorted(glob.glob("out/make/*.glb")):
            for az in ("0.6", "2.4"):
                pg.goto(f"http://127.0.0.1:8765/.github/hf_view/view.html?f=/{f}&az={az}")
                pg.wait_for_function("window.done === true", timeout=180000)
                pg.screenshot(path=f.replace(".glb", f"_view{az}.png"))
                print("::notice title=view::" + f + " " + az + " " + pg.title())
        b.close()
finally:
    srv.terminate()

import asyncio, json, os, time, base64
import websockets
KEY = os.environ["GOOGLE_API"].strip()
URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + KEY
SPEECH = "Кавказский акцент, говорит неспешно, тепло. Вставляет «слушай, брат», «Иншаллах»."

async def run(model, voice, ric, speech, label):
    setup = {"model": f"models/{model}",
             "generationConfig": {"responseModalities": ["AUDIO"],
                                  "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}}},
             "systemInstruction": {"parts": [{"text": "Ты Мустафа, друг Алима. Говори по-русски коротко." + (" Манера: " + SPEECH if speech else "")}]},
             "inputAudioTranscription": {}, "outputAudioTranscription": {},
             "contextWindowCompression": {"slidingWindow": {}}}
    if ric:
        setup["realtimeInputConfig"] = {"activityHandling": "NO_INTERRUPTION",
                                        "automaticActivityDetection": {"startOfSpeechSensitivity": "START_SENSITIVITY_LOW", "prefixPaddingMs": 200}}
    t0 = time.time(); res = {"label": label}
    try:
        async with websockets.connect(URL, max_size=None, open_timeout=20) as ws:
            await ws.send(json.dumps({"setup": setup}))
            m = json.loads(await asyncio.wait_for(ws.recv(), 20))
            res["setup_s"] = round(time.time() - t0, 2); res["setup"] = list(m.keys())
            # 1 с тишины как микрофон, затем «звонок»
            sil = base64.b64encode(b"\0" * 3200).decode()
            for _ in range(10):
                await ws.send(json.dumps({"realtimeInput": {"audio": {"data": sil, "mimeType": "audio/pcm;rate=16000"}}}))
            t1 = time.time()
            await ws.send(json.dumps({"realtimeInput": {"text": "(Звонит Алим. Ты снял трубку — скажи только «Алло?» и жди.)"}}))
            first = None; audio = 0; chunks = []; txt = ""
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), 25))
                sc = m.get("serverContent", {})
                for p in sc.get("modelTurn", {}).get("parts", []):
                    d = p.get("inlineData")
                    if d:
                        now = time.time()
                        if first is None: first = now
                        chunks.append(now); audio += len(base64.b64decode(d["data"]))
                txt += sc.get("outputTranscription", {}).get("text", "")
                if sc.get("turnComplete"): break
            gaps = [round(b - a, 2) for a, b in zip(chunks, chunks[1:])]
            res.update(first_audio_s=round(first - t1, 2) if first else None, audio_s=round(audio / 48000, 2),
                       gen_s=round(chunks[-1] - chunks[0], 2) if chunks else 0, max_gap=max(gaps) if gaps else 0, n=len(chunks), said=txt[:80])
    except Exception as e:
        res["error"] = f"{type(e).__name__}: {str(e)[:300]}"
    print(json.dumps(res, ensure_ascii=False), flush=True)

async def main():
    for model in ["gemini-3.8-live", "gemini-2.5-flash-native-audio-preview-12-2025"]:
        for voice in ["Orus", "Algenib"]:
            await run(model, voice, True, True, f"{model} {voice} ric+speech")
        await run(model, "Orus", False, False, f"{model} Orus plain")
asyncio.run(main())

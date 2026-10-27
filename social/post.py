#!/usr/bin/env python3
"""Publica un mensaje en Discord, Facebook, Instagram y YouTube.

Uso:
  python post.py "Nueva versión 1.2 disponible!"
  python post.py "Mirá la novedad" --image https://ejemplo.com/foto.jpg
  python post.py "Trailer" --video ./trailer.mp4 --title "Trailer v1.2"
  python post.py "Solo discord" --only discord

Credenciales por variables de entorno (ver .env.example). Cada red se omite
si faltan sus credenciales; un error en una red no frena a las demás.
"""
import argparse
import os
import sys
import time

import requests

GRAPH = "https://graph.facebook.com/v21.0"


def is_url(s):
    return bool(s) and s.startswith(("http://", "https://"))


def env(*names):
    vals = [os.environ.get(n) for n in names]
    return vals if all(vals) else None


def check(r):
    if not r.ok:
        raise RuntimeError(f"{r.status_code}: {r.text[:300]}")
    return r.json() if r.content else {}


def post_discord(text, image, video, title):
    cfg = env("DISCORD_WEBHOOK_URL")
    if not cfg:
        return "skip: falta DISCORD_WEBHOOK_URL"
    payload = {"content": text}
    if image and is_url(image):
        payload["embeds"] = [{"image": {"url": image}}]
    files = None
    if video and not is_url(video):
        files = {"file": open(video, "rb")}  # límite de Discord: ~25 MB
    elif video:
        payload["content"] += f"\n{video}"
    if files:
        r = requests.post(cfg[0], data={"payload_json": __import__("json").dumps(payload)}, files=files)
    else:
        r = requests.post(cfg[0], json=payload)
    check(r)
    return "ok"


def post_facebook(text, image, video, title):
    cfg = env("FB_PAGE_ID", "FB_PAGE_TOKEN")
    if not cfg:
        return "skip: faltan FB_PAGE_ID / FB_PAGE_TOKEN"
    page, token = cfg
    if video:
        if is_url(video):
            data = {"file_url": video, "description": text, "title": title or "", "access_token": token}
            r = requests.post(f"{GRAPH}/{page}/videos", data=data)
        else:
            data = {"description": text, "title": title or "", "access_token": token}
            r = requests.post(f"https://graph-video.facebook.com/v21.0/{page}/videos",
                              data=data, files={"source": open(video, "rb")})
    elif image:
        data = {"caption": text, "access_token": token}
        if is_url(image):
            data["url"] = image
            r = requests.post(f"{GRAPH}/{page}/photos", data=data)
        else:
            r = requests.post(f"{GRAPH}/{page}/photos", data=data, files={"source": open(image, "rb")})
    else:
        r = requests.post(f"{GRAPH}/{page}/feed", data={"message": text, "access_token": token})
    return f"ok id={check(r).get('id') or check(r).get('post_id')}"


def post_instagram(text, image, video, title):
    cfg = env("IG_USER_ID", "IG_ACCESS_TOKEN")
    if not cfg:
        return "skip: faltan IG_USER_ID / IG_ACCESS_TOKEN"
    user, token = cfg
    media = video or image
    if not media:
        return "skip: Instagram requiere imagen o video"
    if not is_url(media):
        return "skip: Instagram requiere una URL pública (no archivo local)"
    data = {"caption": text, "access_token": token}
    data.update({"media_type": "REELS", "video_url": media} if video else {"image_url": media})
    cid = check(requests.post(f"{GRAPH}/{user}/media", data=data))["id"]
    for _ in range(40):  # esperar a que Instagram procese el medio
        st = check(requests.get(f"{GRAPH}/{cid}", params={"fields": "status_code", "access_token": token}))
        if st.get("status_code") == "FINISHED":
            break
        if st.get("status_code") == "ERROR":
            raise RuntimeError("Instagram no pudo procesar el medio")
        time.sleep(5)
    pub = check(requests.post(f"{GRAPH}/{user}/media_publish", data={"creation_id": cid, "access_token": token}))
    return f"ok id={pub.get('id')}"


def post_youtube(text, image, video, title):
    cfg = env("YT_CLIENT_ID", "YT_CLIENT_SECRET", "YT_REFRESH_TOKEN")
    if not cfg:
        return "skip: faltan YT_CLIENT_ID / YT_CLIENT_SECRET / YT_REFRESH_TOKEN"
    if not video or is_url(video):
        return "skip: YouTube requiere un archivo de video local (--video)"
    cid, secret, refresh = cfg
    tok = check(requests.post("https://oauth2.googleapis.com/token", data={
        "client_id": cid, "client_secret": secret, "refresh_token": refresh, "grant_type": "refresh_token"}))
    auth = {"Authorization": f"Bearer {tok['access_token']}"}
    meta = {"snippet": {"title": (title or text)[:100], "description": text},
            "status": {"privacyStatus": os.environ.get("YT_PRIVACY", "public")}}
    init = requests.post(
        "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status",
        headers={**auth, "X-Upload-Content-Type": "video/*"}, json=meta)
    check(init)
    with open(video, "rb") as f:
        up = requests.put(init.headers["Location"], data=f, headers={**auth, "Content-Type": "video/*"})
    return f"ok https://youtu.be/{check(up).get('id')}"


NETWORKS = {"discord": post_discord, "facebook": post_facebook,
            "instagram": post_instagram, "youtube": post_youtube}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("text", help="texto del post")
    ap.add_argument("--image", help="URL pública o archivo de imagen")
    ap.add_argument("--video", help="URL pública o archivo de video")
    ap.add_argument("--title", help="título (YouTube/Facebook video)")
    ap.add_argument("--only", help="redes separadas por coma, ej: discord,facebook")
    ap.add_argument("--dry-run", action="store_true", help="no publica, solo muestra qué haría")
    a = ap.parse_args()

    nets = [n.strip() for n in a.only.split(",")] if a.only else list(NETWORKS)
    bad = [n for n in nets if n not in NETWORKS]
    if bad:
        ap.error(f"redes desconocidas: {', '.join(bad)}")

    failed = False
    for n in nets:
        if a.dry_run:
            print(f"[{n}] dry-run: {a.text!r}")
            continue
        try:
            res = NETWORKS[n](a.text, a.image, a.video, a.title)
        except Exception as e:  # noqa: BLE001 - seguir con las demás redes
            res, failed = f"ERROR {e}", True
        print(f"[{n}] {res}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

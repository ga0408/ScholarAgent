"""Slide renderer (HTML->PNG), Korean neural TTS (edge-tts), and ffmpeg MP4 composer."""

from __future__ import annotations

import asyncio
import html
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import edge_tts
from PIL import Image, ImageDraw, ImageFont

SECTION_LABELS: dict[str, tuple[str, str]] = {
    "title": ("PAPER REVIEW", "#6366F1"),
    "overview": ("1. OVERVIEW & TL;DR", "#0EA5E9"),
    "introduction": ("2. INTRODUCTION", "#10B981"),
    "related_work": ("3. BACKGROUND & RELATED WORK", "#8B5CF6"),
    "method": ("4. MAIN METHOD", "#F59E0B"),
    "experiment": ("5. EXPERIMENTS", "#EC4899"),
    "conclusion": ("6. CONCLUSION & LIMITATIONS", "#64748B"),
}


def _find_chrome() -> str | None:
    for candidate in ("google-chrome", "chromium", "chromium-browser"):
        found = shutil.which(candidate)
        if found:
            return found
    return None


def _find_ffmpeg() -> str:
    for name in ("ffmpeg",):
        found = shutil.which(name)
        if found:
            return found
    local_bin = Path.home() / ".local" / "bin" / "ffmpeg"
    if local_bin.exists():
        return str(local_bin)
    raise RuntimeError("ffmpeg binary not found")


def _find_ffprobe() -> str | None:
    found = shutil.which("ffprobe")
    if found:
        return found
    local_bin = Path.home() / ".local" / "bin" / "ffprobe"
    return str(local_bin) if local_bin.exists() else None


def _build_slide_html(
    slide: dict[str, Any],
    *,
    deck_title: str,
    deck_subtitle: str,
    total_slides: int,
    page_img_uri: str | None,
) -> str:
    sec_key = str(slide.get("section", "overview")).lower()
    badge_label, badge_color = SECTION_LABELS.get(sec_key, (sec_key.upper(), "#3B82F6"))
    slide_num = int(slide.get("slide_number", 1))
    title_html = html.escape(str(slide.get("title", "")))
    bullets = slide.get("bullets") or []
    eq_text = str(slide.get("equation_text") or "").strip()
    visual_page = slide.get("visual_page")

    bullets_html = "\n".join(
        f"<li>{html.escape(str(b))}</li>" for b in bullets if str(b).strip()
    )
    eq_html = (
        f'<div class="equation-box"><code>{html.escape(eq_text)}</code></div>'
        if eq_text
        else ""
    )

    if page_img_uri:
        right_panel = f"""
        <div class="right-col">
          <div class="paper-frame">
            <img src="{html.escape(page_img_uri)}" alt="Paper page" />
            <div class="page-caption">Original Paper • Page {html.escape(str(visual_page))}</div>
          </div>
        </div>
        """
        layout_class = "split-layout"
    else:
        right_panel = ""
        layout_class = "full-layout"

    subtitle_bar = (
        f'<div class="deck-sub">{html.escape(deck_subtitle)}</div>'
        if slide_num == 1 and deck_subtitle
        else ""
    )

    return f"""<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8" />
<style>
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  html, body {{
    width: 1920px; height: 1080px; overflow: hidden;
    font-family: "Noto Sans CJK KR", "Noto Sans KR", "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
    background: linear-gradient(135deg, #0F172A 0%, #1E293B 100%);
    color: #F8FAFC;
  }}
  .slide {{
    width: 1920px; height: 1080px; padding: 64px 80px 56px;
    display: flex; flex-direction: column; justify-content: space-between;
  }}
  .header {{
    display: flex; align-items: center; justify-content: space-between;
    margin-bottom: 24px;
  }}
  .badge {{
    display: inline-block; padding: 8px 18px; border-radius: 999px;
    font-size: 20px; font-weight: 700; letter-spacing: 0.04em;
    background: {badge_color}28; color: {badge_color};
    border: 1.5px solid {badge_color};
  }}
  .deck-ref {{ font-size: 20px; color: #94A3B8; max-width: 900px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }}
  .body {{ flex: 1; display: flex; gap: 48px; min-height: 0; align-items: stretch; }}
  .left-col {{ flex: 1.15; display: flex; flex-direction: column; justify-content: center; }}
  .full-layout .left-col {{ max-width: 1680px; }}
  .right-col {{ flex: 0.85; display: flex; align-items: center; justify-content: center; }}
  h1 {{
    font-size: 48px; line-height: 1.25; font-weight: 800; color: #FFFFFF;
    margin-bottom: 24px;
  }}
  .deck-sub {{ font-size: 24px; color: #CBD5E1; margin-bottom: 24px; }}
  ul {{ list-style: none; display: flex; flex-direction: column; gap: 18px; }}
  li {{
    position: relative; padding-left: 34px;
    font-size: 30px; line-height: 1.48; color: #E2E8F0;
  }}
  li::before {{
    content: ""; position: absolute; left: 0; top: 16px;
    width: 12px; height: 12px; border-radius: 50%;
    background: {badge_color};
  }}
  .equation-box {{
    margin-top: 28px; padding: 20px 26px; border-radius: 14px;
    background: #020617AA; border-left: 5px solid {badge_color};
    font-family: "JetBrains Mono", "Fira Code", monospace;
    font-size: 26px; color: #FDE68A; line-height: 1.4;
    word-break: break-word;
  }}
  .paper-frame {{
    background: #FFFFFF; border-radius: 16px; padding: 14px;
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
    display: flex; flex-direction: column; align-items: center;
    max-height: 820px;
  }}
  .paper-frame img {{
    max-height: 750px; max-width: 100%; object-fit: contain; border-radius: 8px;
  }}
  .page-caption {{
    margin-top: 10px; font-size: 18px; font-weight: 600; color: #475569;
  }}
  .footer {{
    display: flex; justify-content: space-between; align-items: center;
    padding-top: 18px; border-top: 1px solid #334155;
    font-size: 20px; color: #94A3B8;
  }}
</style>
</head>
<body>
  <div class="slide">
    <div class="header">
      <span class="badge">{html.escape(badge_label)}</span>
      <span class="deck-ref">{html.escape(deck_title)}</span>
    </div>
    <div class="body {layout_class}">
      <div class="left-col">
        <h1>{title_html}</h1>
        {subtitle_bar}
        <ul>{bullets_html}</ul>
        {eq_html}
      </div>
      {right_panel}
    </div>
    <div class="footer">
      <span>ScholarAgent Auto-Presentation</span>
      <span>{slide_num} / {total_slides}</span>
    </div>
  </div>
</body>
</html>
"""


def _render_slide_pillow_fallback(slide: dict[str, Any], out_png: Path, total_slides: int) -> None:
    img = Image.new("RGB", (1920, 1080), (15, 23, 42))
    draw = ImageDraw.Draw(img)
    font_title = ImageFont.load_default()
    draw.text((80, 120), str(slide.get("title", "")), fill=(255, 255, 255), font=font_title)
    y = 240
    for b in slide.get("bullets") or []:
        draw.text((100, y), f"- {b}", fill=(226, 232, 240), font=font_title)
        y += 60
    draw.text((1780, 1010), f"{slide.get('slide_number', 1)}/{total_slides}", fill=(148, 163, 184), font=font_title)
    img.save(str(out_png))


def render_slides(
    presentation: dict[str, Any],
    work_dir: Path,
    slides_dir: Path,
) -> list[Path]:
    slides_dir.mkdir(parents=True, exist_ok=True)
    slides = presentation["slides"]
    total = len(slides)
    chrome = _find_chrome()
    pages_dir = work_dir / "pages"
    png_paths: list[Path] = []

    for slide in slides:
        num = int(slide["slide_number"])
        vpage = slide.get("visual_page")
        page_uri: str | None = None
        if isinstance(vpage, int) and vpage > 0:
            candidate = pages_dir / f"page_{vpage:03d}.png"
            if candidate.exists():
                page_uri = candidate.resolve().as_uri()

        html_text = _build_slide_html(
            slide,
            deck_title=presentation.get("title", ""),
            deck_subtitle=presentation.get("subtitle", ""),
            total_slides=total,
            page_img_uri=page_uri,
        )
        html_path = slides_dir / f"slide_{num:03d}.html"
        png_path = slides_dir / f"slide_{num:03d}.png"
        html_path.write_text(html_text, encoding="utf-8")

        if chrome:
            cmd = [
                chrome,
                "--headless",
                "--no-sandbox",
                "--disable-gpu",
                "--hide-scrollbars",
                f"--screenshot={png_path}",
                "--window-size=1920,1080",
                html_path.resolve().as_uri(),
            ]
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30, check=False)
            if proc.returncode != 0 or not png_path.exists():
                _render_slide_pillow_fallback(slide, png_path, total)
        else:
            _render_slide_pillow_fallback(slide, png_path, total)

        png_paths.append(png_path)

    return png_paths


async def _tts_one(text: str, voice: str, out_mp3: Path) -> None:
    communicate = edge_tts.Communicate(text, voice=voice)
    await communicate.save(str(out_mp3))


def synthesize_slide_audio(
    slides: list[dict[str, Any]],
    audio_dir: Path,
    *,
    voice: str = "ko-KR-InJoonNeural",
    force: bool = False,
) -> list[Path]:
    audio_dir.mkdir(parents=True, exist_ok=True)
    mp3_paths: list[Path] = []

    async def _run_all() -> None:
        for slide in slides:
            num = int(slide["slide_number"])
            out_mp3 = audio_dir / f"slide_{num:03d}.mp3"
            mp3_paths.append(out_mp3)
            if out_mp3.exists() and out_mp3.stat().st_size > 1024 and not force:
                continue
            script = " ".join(str(slide.get("speaker_script", "")).split())
            if not script:
                script = str(slide.get("title", "다음 슬라이드입니다."))
            await _tts_one(script, voice, out_mp3)

    asyncio.run(_run_all())
    return mp3_paths


def _probe_duration_seconds(media_path: Path, ffmpeg_bin: str) -> float:
    ffprobe = _find_ffprobe()
    if ffprobe:
        proc = subprocess.run(
            [
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(media_path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode == 0:
            try:
                return max(0.5, float(proc.stdout.strip()))
            except ValueError:
                pass

    # Fallback: parse Duration from ffmpeg stderr
    proc = subprocess.run(
        [ffmpeg_bin, "-i", str(media_path)],
        capture_output=True,
        text=True,
        check=False,
    )
    m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", proc.stderr)
    if m:
        h, mins, secs = int(m.group(1)), int(m.group(2)), float(m.group(3))
        return max(0.5, h * 3600 + mins * 60 + secs)
    return 5.0


def _fmt_srt_time(seconds: float) -> str:
    ms_total = int(round(seconds * 1000))
    hrs = ms_total // 3_600_000
    rem = ms_total % 3_600_000
    mins = rem // 60_000
    rem %= 60_000
    secs = rem // 1000
    ms = rem % 1000
    return f"{hrs:02d}:{mins:02d}:{secs:02d},{ms:03d}"


def _write_srt(
    slides: list[dict[str, Any]],
    durations: list[float],
    srt_path: Path,
) -> None:
    lines: list[str] = []
    idx = 1
    cursor = 0.0
    for slide, dur in zip(slides, durations):
        script = " ".join(str(slide.get("speaker_script", "")).split())
        if not script:
            cursor += dur
            continue
        sentences = [s.strip() for s in re.split(r"(?<=[.!?다요죠])\s+", script) if s.strip()]
        if not sentences:
            sentences = [script]
        total_chars = max(1, sum(len(s) for s in sentences))
        seg_start = cursor
        for s in sentences:
            part_dur = dur * (len(s) / total_chars)
            seg_end = seg_start + part_dur
            lines.append(f"{idx}")
            lines.append(f"{_fmt_srt_time(seg_start)} --> {_fmt_srt_time(seg_end)}")
            lines.append(s)
            lines.append("")
            idx += 1
            seg_start = seg_end
        cursor += dur
    srt_path.write_text("\n".join(lines), encoding="utf-8")


def build_presentation_video(
    presentation: dict[str, Any],
    work_dir: Path,
    *,
    voice: str = "ko-KR-InJoonNeural",
    force: bool = False,
) -> dict[str, Any]:
    """Render slide PNGs, synthesize TTS MP3s, generate SRT, and compose presentation.mp4."""
    ffmpeg = _find_ffmpeg()
    pres_dir = work_dir / "presentation"
    slides_dir = pres_dir / "slides"
    audio_dir = pres_dir / "audio"
    segs_dir = pres_dir / "segments"
    pres_dir.mkdir(parents=True, exist_ok=True)
    segs_dir.mkdir(parents=True, exist_ok=True)

    slides = presentation["slides"]
    png_paths = render_slides(presentation, work_dir, slides_dir)
    mp3_paths = synthesize_slide_audio(slides, audio_dir, voice=voice, force=force)

    durations: list[float] = []
    seg_paths: list[Path] = []
    for slide, png, mp3 in zip(slides, png_paths, mp3_paths):
        num = int(slide["slide_number"])
        dur = _probe_duration_seconds(mp3, ffmpeg)
        durations.append(dur)
        seg_mp4 = segs_dir / f"seg_{num:03d}.mp4"
        seg_paths.append(seg_mp4)
        subprocess.run(
            [
                ffmpeg,
                "-y",
                "-loop",
                "1",
                "-i",
                str(png),
                "-i",
                str(mp3),
                "-c:v",
                "libx264",
                "-tune",
                "stillimage",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-pix_fmt",
                "yuv420p",
                "-r",
                "30",
                "-shortest",
                str(seg_mp4),
            ],
            capture_output=True,
            text=True,
            check=True,
        )

    concat_list = pres_dir / "concat.txt"
    concat_list.write_text(
        "\n".join(f"file '{p.resolve()}'" for p in seg_paths) + "\n",
        encoding="utf-8",
    )
    out_mp4 = pres_dir / "presentation.mp4"
    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(concat_list),
            "-c",
            "copy",
            str(out_mp4),
        ],
        capture_output=True,
        text=True,
        check=True,
    )

    srt_path = pres_dir / "subtitles.srt"
    _write_srt(slides, durations, srt_path)

    return {
        "presentation_mp4": str(out_mp4),
        "subtitles_srt": str(srt_path),
        "slides_dir": str(slides_dir),
        "slide_count": len(slides),
        "total_duration_seconds": round(sum(durations), 2),
        "voice": voice,
    }

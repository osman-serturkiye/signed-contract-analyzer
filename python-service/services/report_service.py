"""
services/report_service.py — Jinja2 tabanlı HTML rapor üretimi ve
WeasyPrint (veya Playwright) ile PDF dönüşümü.

Tek ReportTemplate (report-template.html) hem HTML hem PDF çıktısı için
kullanılır (Req 17.3, 18.3, 18.5).
"""

from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List

import markdown as md_lib
from jinja2 import Environment, FileSystemLoader, select_autoescape

_TEMPLATE_DIR = Path(__file__).resolve().parent.parent / "templates"

_env = Environment(
    loader=FileSystemLoader(str(_TEMPLATE_DIR)),
    autoescape=select_autoescape(["html"]),
)


def _natural_sort_key(key: str):
    """Doğal madde sıralaması: "1" < "1.1" < "1.2" < "2" < "10" ve
    "N-duplicate-K" anahtarı "N"nin hemen ardından gelir (Req 12.4, 29.3)."""
    base, _, dup = key.partition("-duplicate-")
    parts = [int(p) if p.isdigit() else 0 for p in re.split(r"\.", base)]
    dup_index = int(dup) if dup.isdigit() else 0
    return (parts, dup_index)


def _md_to_html(text: str) -> str:
    if not text:
        return ""
    return md_lib.markdown(text, extensions=["tables", "fenced_code"])


def _diff_css_class(diff: Dict[str, Any] | None) -> str:
    if diff is None:
        return "unknown"
    changes = diff.get("changes")
    if changes is None:
        return "unknown"
    return "changed" if changes else "unchanged"


def _build_rows(analysis: Dict[str, Any]) -> List[Dict[str, Any]]:
    analyze_clauses: Dict[str, Any] = analysis.get("analyze_clauses", {}) or {}
    rows: List[Dict[str, Any]] = []
    for clause_no in sorted(analyze_clauses.keys(), key=_natural_sort_key):
        entry = analyze_clauses[clause_no] or {}
        diff = entry.get("diff") or {}
        diff_ai = entry.get("diff_ai") or {}
        rows.append({
            "clause_no": clause_no,
            "signed_image": entry.get("signed_image"),
            "signed_content_html": _md_to_html(entry.get("signed_content", "")),
            "original_content_html": _md_to_html(entry.get("original_content", "")),
            "java_diff_html": _md_to_html(diff.get("result", "")),
            "java_diff_class": _diff_css_class(diff),
            "ai_diff_html": _md_to_html(diff_ai.get("result", "")),
            "ai_diff_class": _diff_css_class(diff_ai),
        })
    return rows


class ReportService:
    """report-template.html'i render eder ve isteğe bağlı olarak PDF'e çevirir."""

    def render_html(self, report_request: Dict[str, Any]) -> str:
        signed_contract = report_request.get("signed_contract", {}) or {}
        analysis = report_request.get("analysis", {}) or {}

        template = _env.get_template("report-template.html")
        return template.render(
            title=signed_contract.get("title") or "Sözleşme Analiz Raporu",
            generated_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            rows=_build_rows(analysis),
        )

    def render_pdf(self, report_request: Dict[str, Any], use_playwright: bool = False) -> bytes:
        html = self.render_html(report_request)

        if use_playwright:
            return self._html_to_pdf_playwright(html)
        return self._html_to_pdf_weasyprint(html)

    @staticmethod
    def _html_to_pdf_weasyprint(html: str) -> bytes:
        from weasyprint import HTML  # local import: heavy dependency
        return HTML(string=html).write_pdf()

    @staticmethod
    def _html_to_pdf_playwright(html: str) -> bytes:
        from playwright.sync_api import sync_playwright  # local import: optional dependency
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_page()
            page.set_content(html, wait_until="networkidle")
            pdf_bytes = page.pdf(format="A4", print_background=True)
            browser.close()
            return pdf_bytes

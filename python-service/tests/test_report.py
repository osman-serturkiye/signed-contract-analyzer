"""
tests/test_report.py — /report/html ve /report/pdf endpoint birim testleri.

Gereksinimler: 17.3, 17.4, 18.3, 18.4
"""

from __future__ import annotations

import os

os.environ.setdefault("SERVICE_API_KEY", "test-key")

from config import get_settings
get_settings.cache_clear()

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.testclient import TestClient

from routers import report as report_router

_app = FastAPI()
_app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
_app.include_router(report_router.router, prefix="/report", tags=["report"])

client = TestClient(_app, raise_server_exceptions=False)
AUTH = {"Authorization": "Bearer test-key"}

SAMPLE_REQUEST = {
    "signed_contract": {"title": "Test Sözleşmesi"},
    "original_contract": {"title": "Test Sözleşmesi"},
    "analysis": {
        "analyze_clauses": {
            "2": {
                "signed_image": None,
                "signed_content": "## 2. Madde",
                "original_content": "## 2. Madde",
                "diff": {"changes": False, "result": ""},
                "diff_ai": {"changes": False, "result": "Aynı"},
            },
            "1": {
                "signed_image": None,
                "signed_content": "## 1. Madde\n\n**yeni**",
                "original_content": "## 1. Madde\n\n eski",
                "diff": {"changes": True, "result": "- eski\n+ **yeni**"},
                "diff_ai": {"changes": None, "result": "AI service error: timeout"},
            },
        }
    },
}


def test_report_html_returns_200_and_content_type():
    resp = client.post("/report/html", json=SAMPLE_REQUEST, headers=AUTH)
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]


def test_report_html_contains_natural_clause_order():
    resp = client.post("/report/html", json=SAMPLE_REQUEST, headers=AUTH)
    html = resp.text
    # "1" madde satırı "2" madde satırından önce gelmelidir (doğal sıralama).
    assert html.index(">1<") < html.index(">2<")


def test_report_html_no_auth_returns_401():
    resp = client.post("/report/html", json=SAMPLE_REQUEST)
    assert resp.status_code == 401


def test_report_pdf_no_auth_returns_401():
    resp = client.post("/report/pdf", json=SAMPLE_REQUEST)
    assert resp.status_code == 401


def test_report_html_renders_markdown_to_html():
    resp = client.post("/report/html", json=SAMPLE_REQUEST, headers=AUTH)
    assert "<strong>yeni</strong>" in resp.text

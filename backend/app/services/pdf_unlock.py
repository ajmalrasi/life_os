"""Decrypt bank statement PDFs and extract their text.

Password derivation: Indian banks build statement passwords from the holder's
name and DOB. From ``cc_owner_name`` + ``cc_owner_dob`` (DDMMYYYY) we derive
the known schemes (verified against real statements):

- HDFC / Axis: first 4 letters of name UPPER + DDMM   -> AJMA0107
- ICICI:       first 4 letters of name lower + DDMM   -> ajma0107
- SBI:         bank-specific; supply via ``cc_pdf_password_overrides``
               (comma-separated ``bank:password`` pairs, e.g. ``sbi:0107...``)

All candidates are tried in order, so a future card whose bank switches scheme
still opens as long as one candidate matches.
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


def password_candidates(name: str, dob_ddmmyyyy: str, overrides: dict[str, str], bank: str) -> list[str]:
    cands: list[str] = []
    if bank in overrides:
        cands.append(overrides[bank])
    first4 = (name.strip()[:4] if name else "").replace(" ", "")
    ddmm = dob_ddmmyyyy[:4] if dob_ddmmyyyy else ""
    if first4 and ddmm:
        cands += [first4.upper() + ddmm, first4.lower() + ddmm]
    if dob_ddmmyyyy:
        cands += [dob_ddmmyyyy, dob_ddmmyyyy[:4] + dob_ddmmyyyy[-2:]]
    # De-dup, preserve order.
    seen: set[str] = set()
    return [c for c in cands if not (c in seen or seen.add(c))]


def parse_overrides(raw: str) -> dict[str, str]:
    """Parse 'sbi:pass1,axis:pass2' into a dict."""
    out: dict[str, str] = {}
    for part in (raw or "").split(","):
        if ":" in part:
            bank, pw = part.split(":", 1)
            out[bank.strip().lower()] = pw.strip()
    return out


def extract_pdf_text(pdf_path: str, passwords: list[str]) -> str | None:
    """Decrypt (trying passwords in order; '' first for unencrypted) and
    return the full extracted text, or None if nothing opens it."""
    import pikepdf

    for pw in ["", *passwords]:
        try:
            with pikepdf.open(pdf_path, password=pw) as pdf:
                decrypted = pdf_path + ".dec.pdf"
                pdf.save(decrypted)
            break
        except pikepdf.PasswordError:
            continue
        except Exception as exc:  # corrupt file etc.
            logger.warning("pdf open failed (%s): %s", pdf_path, exc)
            return None
    else:
        logger.warning("no password candidate opened %s", pdf_path)
        return None

    import pdfplumber

    try:
        with pdfplumber.open(decrypted) as doc:
            return "\n".join((page.extract_text() or "") for page in doc.pages)
    except Exception as exc:
        logger.warning("pdf text extraction failed (%s): %s", pdf_path, exc)
        return None
    finally:
        import os

        try:
            os.remove(decrypted)
        except OSError:
            pass

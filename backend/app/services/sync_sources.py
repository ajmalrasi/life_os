"""Canonical source-name constants for sync-attempt tracking and /status/sync.

Kept in their own tiny module so routes, the status service, and
NotificationService can all reference the same strings without importing
each other.
"""

from __future__ import annotations

SOURCE_BANK_MAIL = "bank_mail"
SOURCE_STATEMENTS = "statements"

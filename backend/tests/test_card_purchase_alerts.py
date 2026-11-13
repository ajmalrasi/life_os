from dataclasses import replace
from datetime import date
from decimal import Decimal

from app.mail.models import MailMessage
from app.services.expense_parsers.card_purchase_alerts import (
    AxisAmexPurchaseParser,
    RblCardPurchaseParser,
)


def test_axis_amex_purchase():
    mail = MailMessage(
        message_id="axis",
        subject="INR 620 spent on credit card no. XX2545",
        from_addr="Axis Bank Alerts <alerts@axis.bank.in>",
        received="",
        raw_body="",
        text="""Here's the summary of your Axis Bank Credit Card Transaction:
Transaction Amount:
INR 620
Merchant Name:
EXAMPLE STORE
Axis Bank Credit Card No.
XX2545
Date & Time:
13-11-2026, 20:45:07 IST
Available Limit:
INR 1000""",
    )
    parser = AxisAmexPurchaseParser()
    txn, = parser.parse(mail)
    assert (txn.amount, txn.txn_date, txn.counterparty) == (
        Decimal("620"), date(2026, 11, 13), "EXAMPLE STORE",
    )
    assert txn.account == "Axis American Express ..2545"
    assert txn.txn_at.isoformat() == "2026-11-13T20:45:07+05:30"
    assert not parser.parse(replace(mail, subject="Upcoming autopay transaction on your Axis Bank Card"))
    assert not parser.parse(replace(mail, text=mail.text.replace("XX2545", "XX3068")))


def test_rbl_card_purchase():
    mail = MailMessage(
        message_id="rbl",
        subject="Alert: Your RBL Bank Credit Card has just been swiped",
        from_addr="RBL Bank <RBLAlerts@rbl.bank.in>",
        received="",
        raw_body="",
        text="Card swipe INR600.00 spent at EXAMPLE FUELS on RBL Bank credit card "
             "(3132) on 31-10-2026.AVL limit- INR24,400.00.",
    )
    parser = RblCardPurchaseParser()
    txn, = parser.parse(mail)
    assert (txn.amount, txn.txn_date, txn.counterparty) == (
        Decimal("600.00"), date(2026, 10, 31), "EXAMPLE FUELS",
    )
    assert txn.account == "RBL Bank Credit Card ..3132"
    assert not parser.parse(replace(mail, subject="Alert: Updated transaction settings on your Credit Card"))
    assert not parser.parse(replace(mail, text=mail.text.replace("31-10-2026", "32-10-2026")))

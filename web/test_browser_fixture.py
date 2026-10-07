"""Disposable, synthetic Money ledger for browser checks. Never uses production."""
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from server import Handler, main

DAY = "2026-10-07"
transactions = [
    {"id":1,"txn_date":DAY,"txn_at":"2026-10-07T10:00:00+05:30","amount":"100.00","currency":"INR","direction":"debit","counterparty":"Test Cafe","category":None,"spend_category":"food","account":"Demo Savings ..1234","source":"upi","reference":"TEST-1"},
    {"id":2,"txn_date":DAY,"amount":"500.00","currency":"INR","direction":"debit","counterparty":"Test EMI","category":None,"spend_category":"emi","account":"Demo Card ..5678","source":"credit_card","reference":"TEST-2"},
    {"id":3,"txn_date":DAY,"amount":"100.00","currency":"INR","direction":"credit","counterparty":"Test Friend Return","category":None,"spend_category":None,"account":"Demo Savings ..1234","source":"upi","reference":"TEST-3"},
    {"id":4,"txn_date":DAY,"amount":"200.00","currency":"INR","direction":"debit","counterparty":"Test Store","category":None,"spend_category":"shopping","account":"Demo Card ..5678","source":"credit_card","reference":"TEST-4"},
]
budgets = [{"category":"food","monthly_limit":"1000.00","spent_so_far":"100.00","projected_month_total":"442.86","days_elapsed":7,"days_in_month":31,"on_pace_to_exceed":False,"projected_exceed_day":None}]
profile = {"monthly_income":"100000.00","savings_goal_pct":20,"updated_at":None}
statement = {"id":1,"bank":"demo","card_label":"Demo Card","card_last4":"5678","statement_date":"2026-09-30","period_start":"2026-09-01","period_end":"2026-09-30","due_date":"2026-10-15","total_due":"1000.00","min_due":"100.00","total_spend":"1000.00","status":"due","paid_at":None,"details_status":"ready"}
cycle = {"bank":"demo","card_label":"Demo Card","card_last4":"5678","cycle_start":"2026-10-01","cycle_end":"2026-10-31","current_spend":"700.00","refunds":"0","net_cycle_amount":"700.00","transaction_count":2,"last_transaction_date":DAY,"unconverted_transaction_count":0}
adjustments = {}


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, data, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if status != 204:
            self.wfile.write(json.dumps(data).encode())

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/expenses": return self.reply(transactions)
        if path == "/budgets": return self.reply(budgets)
        if path == "/statements": return self.reply([statement])
        if path == "/money-profile": return self.reply(profile)
        if path == "/status/sync": return self.reply({"sources":[]})
        if path == "/expenses/recurring": return self.reply([{"counterparty":"Test Subscription","cadence":"monthly","occurrences":3,"median_amount":"30","last_amount":"35","last_date":"2026-10-01","next_expected_date":"2026-11-01","price_change_pct":16.67}])
        if path.endswith("/repayment-candidates"): return self.reply([{"transaction":transactions[2],"match_score":95,"reason":"Same amount, later credit"}])
        if path == "/statements/cycles/current": return self.reply({"as_of":DAY,"current_spend_total":"700","refund_total":"0","net_cycle_total":"700","cycles":[cycle]})
        if path == "/statements/cycles/current/demo/5678": return self.reply({"cycle":cycle,"transactions":[transactions[1],transactions[3]]})
        if path == "/statements/1/details": return self.reply({"statement":statement,"items":[{"id":11,"sequence":1,"txn_date":"2026-09-20","txn_time":None,"description":"Test Statement Purchase","amount":"1000","direction":"debit","item_type":"purchase"}],"breakdown":[{"key":"purchase","label":"New charges","total":"1000","count":1}]})
        return self.reply({"detail":"Fixture endpoint missing"},404)

    def mutate(self):
        path = self.path.split("?")[0]
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length",0))) or b"{}")
        if self.command == "PUT" and path == "/money-profile": profile.update(body); return self.reply(profile)
        if self.command == "PUT" and path.startswith("/budgets/"):
            category=path.split("/")[-1]
            obj=next((b for b in budgets if b["category"]==category),None)
            if obj is None: obj={**budgets[0],"category":category}; budgets.append(obj)
            obj.update(body)
            return self.reply(obj)
        if self.command == "PATCH" and path.endswith("/category"):
            txn=next(t for t in transactions if t["id"]==int(path.split("/")[2]))
            txn["spend_category"]=body["category"] or "food"
            return self.reply(txn)
        if self.command == "PATCH" and path.endswith("/paid"):
            statement.update({"paid_at":DAY if body["paid"] else None,"status":"paid" if body["paid"] else "due"})
            return self.reply(statement)
        if self.command == "POST" and path in {"/expenses/refunds","/expenses/repayments"}:
            ids=[body["debit_transaction_id"]]
            if "credit_transaction_id" in body: ids.append(body["credit_transaction_id"])
            changes=[]
            for txn in transactions:
                if txn["id"] in ids: changes.append((txn,txn["spend_category"])); txn["spend_category"]=None
            adjustments[1]=changes
            return self.reply({"id":1})
        if self.command == "DELETE" and re.fullmatch(r"/expenses/(refunds|repayments)/1",path):
            for txn,category in adjustments.pop(1): txn["spend_category"]=category
            return self.reply({"id":1})
        if self.command == "POST" and path.endswith("/sync"): return self.reply({"message":"Fixture source updated"})
        return self.reply({"detail":"Fixture action missing"},404)

    do_POST=mutate
    do_PATCH=mutate
    do_PUT=mutate
    do_DELETE=mutate


if __name__ == "__main__":
    upstream = ThreadingHTTPServer(("127.0.0.1",0),Fixture)
    threading.Thread(target=upstream.serve_forever,daemon=True).start()
    proxy = ThreadingHTTPServer(("127.0.0.1",8092),Handler)
    proxy.backend=f"http://127.0.0.1:{upstream.server_port}"
    proxy.allowed_hosts={"127.0.0.1","localhost"}
    proxy.api_token=""
    proxy.money_sync={"running":False,"step":"Not run yet","errors":[]}
    proxy.money_sync_lock=threading.Lock()
    print("Synthetic Money test app on http://127.0.0.1:8092",flush=True)
    proxy.serve_forever()

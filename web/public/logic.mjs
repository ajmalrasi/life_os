export function istDay(now = new Date()) {
  return new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(now);
}
export function cents(value) { const [whole, fraction=''] = String(value ?? '0').split('.'); return Number(whole)*100 + (String(whole).startsWith('-') ? -1 : 1)*Number((fraction+'00').slice(0,2)); }
export const money = value => new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2}).format(value);
export const pretty = value => String(value ?? '').replaceAll('_',' ').replace(/\b\w/g,c=>c.toUpperCase());
export function spendRows(rows, day, includeEmi=false) {
  return rows.filter(t=>t.currency==='INR' && t.direction==='debit' && t.spend_category != null && t.txn_date<=day && t.txn_date.slice(0,7)===day.slice(0,7) && (includeEmi || !['emi','emi_fees'].includes(t.spend_category)));
}
export function sum(rows) {return rows.reduce((total,t)=>total+cents(t.amount),0)/100;}
export function latestBills(rows) {
  const cards = new Map();
  for (const row of rows) {const key=row.bank+'|'+(row.card_last4 || row.card_label); const previous=cards.get(key); if (!previous || (row.statement_date || row.due_date || '')>(previous.statement_date || previous.due_date || '')) cards.set(key,row);}
  return [...cards.values()].filter(s=>!s.paid_at && cents(s.total_due)>0).sort((a,b)=>(a.due_date || '9999').localeCompare(b.due_date || '9999'));
}

import {cents,istDay} from './logic.mjs';
export const EMI=new Set(['emi','emi_fees']);
export const budgetCategories=['food','groceries','shopping','fuel','subscriptions','entertainment','vehicle','insurance','taxes','legal_fees','travel','bills','utilities','health'];
export const categories=[...budgetCategories,'internet','medical','education','rent','investments','wallet_load','emi','emi_fees','other'];
export const categoryName=key=>({emi:'EMI',emi_fees:'EMI fees',vehicle:'Vehicle maintenance',taxes:'Taxes & government fees',legal_fees:'Professional services'}[key] || String(key || 'Excluded').replaceAll('_',' ').replace(/^./,c=>c.toUpperCase()));
export function plusDays(day,count){const d=new Date(day+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+count);return d.toISOString().slice(0,10);}
export function plusMonths(day,count){const d=new Date(day+'T12:00:00Z'),n=d.getUTCDate();d.setUTCDate(1);d.setUTCMonth(d.getUTCMonth()+count);const max=new Date(Date.UTC(d.getUTCFullYear(),d.getUTCMonth()+1,0)).getUTCDate();d.setUTCDate(Math.min(n,max));return d.toISOString().slice(0,10);}
const monthEnd=day=>plusDays(plusMonths(day.slice(0,7)+'-01',1),-1);
export function sliceFor(period,anchor,custom){
 const first=anchor.slice(0,7)+'-01';
 if(period==='day')return {start:anchor,end:anchor};
 if(period==='week'){const weekday=(new Date(anchor+'T12:00:00Z').getUTCDay()+6)%7;const start=plusDays(anchor,-weekday);return {start,end:plusDays(start,6)};}
 if(period==='six')return {start:plusMonths(first,-5),end:monthEnd(anchor)};
 if(period==='year')return {start:anchor.slice(0,4)+'-01-01',end:anchor.slice(0,4)+'-12-31'};
 if(period==='custom' && custom?.start && custom?.end)return custom.start<=custom.end?custom:{start:custom.end,end:custom.start};
 return {start:first,end:monthEnd(anchor)};
}
export function previousSlice(period,slice){
 if(period==='month'){const start=plusMonths(slice.start,-1);return {start,end:monthEnd(start)};}
 if(period==='six')return {start:plusMonths(slice.start,-6),end:plusDays(slice.start,-1)};
 if(period==='year')return {start:(Number(slice.start.slice(0,4))-1)+'-01-01',end:(Number(slice.start.slice(0,4))-1)+'-12-31'};
 const days=Math.round((new Date(slice.end)-new Date(slice.start))/86400000)+1;
 return {start:plusDays(slice.start,-days),end:plusDays(slice.start,-1)};
}
export function bankAccounts(rows){const result=new Map();for(const row of rows){if(row.source?.toLowerCase()!=='upi' || !/\bsavings?\b/i.test(row.account || ''))continue;const id=accountIdentity(row.account);if(id)result.set(id.key,id);}return [...result.values()].sort((a,b)=>a.label.localeCompare(b.label));}
function accountIdentity(account){const suffix=/\.\.\s*(\d+)\s*$/.exec(account || ''),bank=(account || '').trim().split(' ')[0].toLowerCase();return suffix&&bank?{key:bank+':'+suffix[1],label:bank.toUpperCase()+' ····'+suffix[1]}:null;}
export function eligibleRows(rows,state){const known=new Set(bankAccounts(rows).map(a=>a.key));return rows.filter(t=>{if(t.direction!=='debit'||t.spend_category==null||t.currency!==state.currency||(!state.includeEmi&&EMI.has(t.spend_category)))return false;if(!state.bank)return true;const key=t.source?.toLowerCase()==='upi'?accountIdentity(t.account)?.key:null;return known.has(key)&&(state.bank==='all-bank'||state.bank===key);});}
export function groupCardSpending(rows, state, slice) {
 if (state.bank) return [];

 const savings = new Set(['hdfc:7672']);
 for (const {account} of rows) {
  if (!/\bsavings?\b/i.test(account || '')) continue;
  const identity = accountIdentity(account);
  if (identity) savings.add(identity.key);
 }

 const groups = new Map();
 for (const transaction of inSlice(eligibleRows(rows, state), slice)) {
  if (state.selected?.length && !state.selected.includes(transaction.spend_category)) continue;

  const account = transaction.account || '';
  if (/\b(?:savings?|debit card)\b/i.test(account)) continue;

  const source = transaction.source?.toLowerCase();
  const identity = accountIdentity(account.replace(/\.\.\s*X+(?=\d)/i, '..'));
  if (identity && savings.has(identity.key) && (source === 'upi' || identity.key.split(':')[1].length > 4)) continue;

  const isCardSource = ['card_live', 'hdfc_card_live', 'credit_card', 'statement_emi'].includes(source);
  const hasCardLabel = /\b(?:card|cc)\b/i.test(account) || identity?.key === 'icici:0010';
  if (!isCardSource && !hasCardLabel) continue;

  // This two-digit suffix is a confirmed alias of SBI ..1970.
  const card = identity?.key === 'sbi:70' ? accountIdentity('SBI Card ..1970') : identity;
  const key = card?.key || 'unidentified';
  const label = card?.label || 'Card not identified';
  const group = groups.get(key) || {key, label, rows: []};
  group.rows.push(transaction);
  groups.set(key, group);
 }
 return [...groups.values()]
  .map(group => ({...group, amount: total(group.rows)}))
  .sort((a, b) => b.amount - a.amount || a.label.localeCompare(b.label));
}
export function inSlice(rows,slice){return rows.filter(t=>t.txn_date>=slice.start&&t.txn_date<=slice.end);}
export function total(rows){return rows.reduce((a,t)=>a+cents(t.amount),0)/100;}
export function groupCategories(rows){const groups=new Map();for(const t of rows){const key=t.spend_category || 'other';const group=groups.get(key)||{category:key,rows:[],amount:0};group.rows.push(t);group.amount+=cents(t.amount);groups.set(key,group);}return [...groups.values()].map(g=>({...g,amount:g.amount/100})).sort((a,b)=>b.amount-a.amount);}
export function sortRows(rows,sort){return [...rows].sort((a,b)=>sort==='highest'?cents(b.amount)-cents(a.amount):sort==='lowest'?cents(a.amount)-cents(b.amount):(sort==='oldest'?1:-1)*((a.txn_date+(a.txn_at || '')).localeCompare(b.txn_date+(b.txn_at || '')) || (a.sequence??a.id)-(b.sequence??b.id)));}
export function chartBuckets(rows,period,slice,today=istDay()){
 const end=slice.end<today?slice.end:today,monthly=period==='year',weekly=period==='six',buckets=[];let day=slice.start;
 while(day<=end){const bucketEnd=monthly?monthEnd(day):weekly?plusDays(day,6):day;const rangeEnd=bucketEnd<end?bucketEnd:end;const transactions=inSlice(rows,{start:day,end:rangeEnd});buckets.push({day,end:rangeEnd,total:total(transactions),count:transactions.length});day=monthly?plusMonths(day,1):plusDays(day,weekly?7:1);}
 return buckets;
}

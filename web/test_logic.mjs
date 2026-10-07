import {test} from 'node:test';
import assert from 'node:assert/strict';
import {istDay, spendRows, sum, latestBills} from './public/logic.mjs';
test('IST day crosses midnight before UTC',()=>assert.equal(istDay(new Date('2026-10-06T20:00:00Z')),'2026-10-07'));
test('everyday spend matches Android exclusions and adds exact paise',()=>{
 const base={currency:'INR',direction:'debit',spend_category:'food',txn_date:'2026-10-07',amount:'0.10'};
 const rows=[base,{...base,amount:'0.20'},{...base,spend_category:'emi',amount:'4000'},{...base,spend_category:'emi_fees',amount:'200'},{...base,spend_category:null,amount:'1000'},{...base,currency:'USD',amount:'10'},{...base,direction:'credit',amount:'500'},{...base,txn_date:'2026-10-08',amount:'100'},{...base,txn_date:'2026-09-30',amount:'100'}];
 assert.equal(sum(spendRows(rows,'2026-10-07')),0.30);
 assert.equal(sum(spendRows(rows,'2026-10-07',true)),4200.30);
});
test('a newer statement supersedes a stale unpaid bill and paid cards disappear',()=>{
 const base={bank:'hdfc',card_label:'Diners',card_last4:'6116',total_due:'1000',paid_at:null};
 assert.deepEqual(latestBills([{...base,id:1,statement_date:'2026-08-01',due_date:'2026-08-20'},{...base,id:2,statement_date:'2026-09-01',due_date:'2026-09-20',paid_at:'2026-09-18'}]),[]);
});

import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sliceFor,previousSlice,plusMonths,bankAccounts,eligibleRows,inSlice,total,sortRows,chartBuckets} from './public/money-logic.mjs';
test('calendar periods use Monday weeks and whole calendar months',()=>{
 assert.deepEqual(sliceFor('week','2026-10-07'),{start:'2026-10-05',end:'2026-10-11'});
 assert.deepEqual(sliceFor('six','2026-10-07'),{start:'2026-05-01',end:'2026-10-31'});
 assert.deepEqual(previousSlice('six',sliceFor('six','2026-10-07')),{start:'2025-11-01',end:'2026-04-30'});
 assert.deepEqual(previousSlice('year',sliceFor('year','2026-10-07')),{start:'2025-01-01',end:'2025-12-31'});
 assert.equal(plusMonths('2024-01-31',1),'2024-02-29');
 assert.deepEqual(previousSlice('custom',sliceFor('custom','2026-10-07',{start:'2026-10-07',end:'2026-10-03'})),{start:'2026-09-28',end:'2026-10-02'});
});
test('bank UPI must be proven savings; same suffix on another bank is not enough',()=>{
 const base={currency:'INR',direction:'debit',spend_category:'food',source:'upi',txn_date:'2026-10-07',amount:'100'};
 const txns=[{...base,id:1,account:'HDFC Savings ..7672'},{...base,id:2,account:'HDFC Card ..7672'},{...base,id:3,account:'ICICI Card ..7672'},{...base,id:4,account:'HDFC RuPay CC ..5473'},{...base,id:5,account:'Unknown'},{...base,id:6,account:'HDFC Savings ..7672',source:'credit_card'}];
 assert.deepEqual(bankAccounts(txns),[{key:'hdfc:7672',label:'HDFC ····7672'}]);
 assert.deepEqual(eligibleRows(txns,{currency:'INR',bank:'all-bank',includeEmi:false}).map(t=>t.id),[1,2]);
});
test('currencies, excluded transfers, EMI fees, and period boundaries do not distort totals',()=>{
 const base={currency:'INR',direction:'debit',spend_category:'food',txn_date:'2026-10-07',amount:'0.10'};
 const txns=[base,{...base,amount:'0.20'},{...base,spend_category:'emi_fees',amount:'200'},{...base,currency:'USD',amount:'100'},{...base,spend_category:null,amount:'2000'},{...base,direction:'credit',amount:'500'},{...base,txn_date:'2026-09-30',amount:'500'}];
 const population=eligibleRows(txns,{currency:'INR',bank:'',includeEmi:false});
 assert.equal(total(inSlice(population,sliceFor('month','2026-10-07'))),0.3);
 assert.equal(total(inSlice(eligibleRows(txns,{currency:'INR',bank:'',includeEmi:true}),sliceFor('month','2026-10-07'))),200.3);
});
test('sorting and chart buckets retain all recorded spending and omit future chart buckets',()=>{
 const rows=[{id:1,txn_date:'2026-10-01',amount:'5.01'},{id:2,txn_date:'2026-10-07',amount:'10.99'}];
 assert.deepEqual(sortRows(rows,'highest').map(t=>t.id),[2,1]);
 const buckets=chartBuckets(rows,'month',sliceFor('month','2026-10-07'),'2026-10-07');
 assert.equal(buckets.length,7);
 assert.equal(total(buckets.map(b=>({amount:b.total}))),16);
});

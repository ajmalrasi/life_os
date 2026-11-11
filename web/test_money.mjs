import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sliceFor,previousSlice,plusMonths,bankAccounts,eligibleRows,groupCardSpending,inSlice,total,sortRows,chartBuckets} from './public/money-logic.mjs';
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

test('card spending uses purchase month, merges label variants, and keeps bank UPI out',()=>{
 const base={currency:'INR',direction:'debit',spend_category:'food',source:'upi',txn_date:'2026-09-30',amount:'100.01'};
 const transactions=[
  {...base,id:1,account:'HDFC RuPay CC ..5473'},
  {...base,id:2,account:'HDFC Bank Card ..5473'},
  {...base,id:3,account:'HDFC Savings ..7672'},
  {...base,id:4,account:'HDFC BANK Card ..7672'},
  {...base,id:5,account:'Axis Savings ..4402'},
  {...base,id:6,account:'Axis Card ..XX4402'},
  {...base,id:7,account:'ICICI Amazon Pay ..0010'},
  {...base,id:8,account:'ICICI Card ..5473',source:'card_live',txn_date:'2026-10-01'},
  {...base,id:9,account:'ICICI Card ..5473',source:'card_live',spend_category:null},
  {...base,id:10,account:'ICICI Card ..5473',source:'card_live',direction:'credit'},
  {...base,id:11,account:'SBI Card ..70',source:'statement_emi',spend_category:'emi'},
  {...base,id:12,account:'SBI Card ..1970',source:'card_live'},
  {...base,id:13,account:'HDFC RuPay CC'},
  {...base,id:14,account:'HDFC RuPay CC ..5473',currency:'USD'},
  {...base,id:15,account:'Axis My Zone Rupay ..0872',source:'statement_emi',spend_category:'emi'},
  {...base,id:16,account:'Axis Debit Card ..5555',source:'card_live'},
 ];
 const settings={currency:'INR',bank:'',includeEmi:false,selected:[]};
 const september=sliceFor('month','2026-09-01');
 const october=sliceFor('month','2026-10-01');
 const groups=groupCardSpending(transactions,settings,september);
 assert.deepEqual(groups.map(g=>[g.key,g.amount,g.rows.map(t=>t.id)]),[
  ['hdfc:5473',200.02,[1,2]],
  ['unidentified',100.01,[13]],
  ['icici:0010',100.01,[7]],
  ['sbi:1970',100.01,[12]],
 ]);
 assert.deepEqual(groupCardSpending(transactions,settings,october).flatMap(g=>g.rows.map(t=>t.id)),[8]);
 assert.deepEqual(groupCardSpending(transactions,{...settings,includeEmi:true},september).find(g=>g.key==='sbi:1970').rows.map(t=>t.id),[11,12]);
 assert.deepEqual(groupCardSpending(transactions,{...settings,bank:'all-bank'},september),[]);
 assert.deepEqual(groupCardSpending(transactions,{...settings,selected:['shopping']},september),[]);
});

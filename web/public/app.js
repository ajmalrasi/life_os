import {istDay, money, pretty, spendRows, sum, latestBills} from './logic.mjs';
import {mountMoney} from './money.mjs';

const main=document.querySelector('#main'), dialog=document.querySelector('#detail');
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const date=value=>value ? new Intl.DateTimeFormat('en-IN',{day:'numeric',month:'short',year:'numeric',timeZone:'Asia/Kolkata'}).format(new Date(value.length===10 ? value+'T12:00:00+05:30' : value)) : 'Date unknown';
const timestamp=value=>value ? new Intl.DateTimeFormat('en-IN',{day:'numeric',month:'short',hour:'numeric',minute:'2-digit',timeZone:'Asia/Kolkata'}).format(new Date(value)) : 'Not received yet';
const errorCard=(title,error)=>`<section class="card"><h2>${escape(title)}</h2><p class="error">${escape(error?.message || 'Could not load. Try Refresh.')}</p></section>`;
const blank=text=>`<p class="empty">${escape(text)}</p>`;
const heading=(title,subtitle='',back=false)=>`${back?'<a class="back" href="#more">Back to More</a>':''}<p class="eyebrow">${escape(subtitle)}</p><h1>${escape(title)}</h1>`;
let generation=0, poll, page='', pulse=null, chatBusy=false, chatHistory=[];

async function api(path,options={}) {
  const response=await fetch('/api'+path,{cache:'no-store',...options,headers:{'Content-Type':'application/json',...options.headers}});
  if(!response.ok) {let detail;try{detail=(await response.json()).detail}catch{};const error=new Error(typeof detail==='string'?detail:response.status===404?'No record yet.':response.status===401?'The server needs its API token configured.':'Could not load. Try again.');error.status=response.status;throw error;}
  return response.status===204?null:response.json();
}
async function load(paths){return Object.fromEntries(await Promise.all(Object.entries(paths).map(async([key,path])=>{try{return [key,{data:await api(path)}]}catch(error){return [key,{error}]}})));}
function notify(text){const node=document.querySelector('#toast');node.textContent=text;node.hidden=false;clearTimeout(notify.timer);notify.timer=setTimeout(()=>node.hidden=true,4500);}
function safeLink(url,label){try{const parsed=new URL(url);if(!['https:','http:'].includes(parsed.protocol))return '';return `<a class="quiet" href="${escape(parsed.href)}" target="_blank" rel="noopener noreferrer">${escape(label)}</a>`;}catch{return '';}}
function openDetail(title,html){document.querySelector('#detail-title').textContent=title;document.querySelector('#detail-body').innerHTML=html;if(!dialog.open)dialog.showModal();}
document.querySelector('#close-detail').onclick=()=>dialog.close();
document.querySelector('#refresh').onclick=()=>route();
window.addEventListener('hashchange',()=>route());
window.addEventListener('online',()=>notify('Connection restored. Tap Refresh for current data.'));
window.addEventListener('offline',()=>notify('Offline. Displayed data may be out of date.'));

function briefHTML(result){
  if(result.error)return errorCard("Today's brief",result.error);
  const data=result.data, brief=data.brief;
  return `<section class="card brief">${brief?`<span class="tag">${escape(pretty(brief.state))}</span><h2>${escape(brief.headline)}</h2><p>${escape(brief.summary)}</p>${brief.suggestion?`<p class="suggestion">${escape(brief.suggestion)}</p>`:''}${brief.workout_note?`<p class="stack ${brief.workout_attention?'warning':'muted'}">Workouts · ${escape(brief.workout_note)}</p>`:''}<p class="meta stack">Written ${escape(timestamp(brief.generated_at))}${data.brief_status==='refreshing'?' · Updating…':''}</p>`:`<h2>Your day</h2>${blank(data.brief_status==='refreshing'?'Your brief is being written.':'Your brief is not available yet.')}`}<p class="meta stack">Health received ${escape(timestamp(data.health_last_received_at))}</p></section>`;
}
function budgetHTML(rows){return rows.length?rows.map(b=>`<div class="list-row"><div><h3>${escape(pretty(b.category))}</h3><p class="meta">${money(Number(b.spent_so_far))} of ${money(Number(b.monthly_limit))}</p><p class="meta ${b.on_pace_to_exceed?'warning':''}">Month-end estimate ${money(Number(b.projected_month_total))}</p></div><span class="value ${b.on_pace_to_exceed?'warning':''}">${Math.round(Number(b.spent_so_far)/Number(b.monthly_limit)*100)}%</span></div>`).join(''):blank('No budgets set.');}
function billsHTML(rows){return rows.length?rows.map(b=>`<div class="list-row"><div><h3>${escape(b.card_label)}</h3><p class="meta ${b.due_date && b.due_date<istDay()?'warning':''}">${b.due_date && b.due_date<istDay()?'Shows past due':'Due'} ${escape(date(b.due_date))}</p></div><strong class="value">${money(Number(b.total_due))}</strong></div>`).join(''):blank('No unpaid bills recorded.');}
function moneySummary(result){
  if(result.error)return errorCard('Money',result.error);
  const rows=spendRows(result.data,istDay()), total=sum(rows), today=sum(rows.filter(t=>t.txn_date===istDay()));
  return `<section class="card"><p class="eyebrow">THIS MONTH · EMI EXCLUDED</p><div class="big">${money(total)}</div><p class="meta stack">Today ${money(today)}</p><div class="actions"><a href="#money">View Money</a></div></section>`;
}
function scoreHTML(result){if(result.error)return errorCard('Health & recovery',result.error);const rows=result.data;const row=rows.slice().sort((a,b)=>b.day.localeCompare(a.day))[0];if(!row)return `<section class="card"><h2>Health & recovery</h2>${blank('No scores yet.')}</section>`;return `<section class="card"><h2>Health & recovery</h2><p class="meta">Scores for ${escape(date(row.day))}</p>${[['sleep_score','Sleep'],['recovery_score','Recovery'],['fitness_score','Fitness'],['execution_score','Execution']].map(([key,label])=>`<div class="stat-row"><span>${label}</span><strong>${row[key]==null?'Unknown':Math.round(row[key])+' / 100'}</strong></div>`).join('')}</section>`;}

function pulseHTML(result){
  if(result.error && result.error.status!==404)return errorCard('Pulse',result.error);
  pulse=result.data || null;
  const selected=pulse?(pulse.mood>=5?3:pulse.mood>=4?2:pulse.mood<=2?0:1):-1;
  return `<section class="card"><p class="eyebrow">PULSE</p><h2>How’s today going?</h2><div class="pulse-options">${[['😞','Rough'],['😐','Meh'],['🙂','Good'],['🔥','Flow']].map(([emoji,label],i)=>`<button data-pulse="${i}" class="${selected===i?'selected':''}" aria-pressed="${selected===i}"><span aria-hidden="true">${emoji}</span>${label}</button>`).join('')}</div><p class="meta" id="pulse-status">${pulse?'Saved for today. Change it anytime.':'One tap saves your check-in.'}</p><form id="note-form" class="note-form"><label class="meta" for="pulse-note">Optional note</label><textarea id="pulse-note" rows="2" maxlength="1000" placeholder="What stood out today?">${escape(pulse?.note || '')}</textarea><div class="actions"><button type="submit" class="quiet" ${pulse?'':'disabled'}>Save note</button><button type="button" id="cancel-note" class="quiet">Cancel</button></div></form></section>`;
}
function wirePulse(){
  const buttons=[...main.querySelectorAll('[data-pulse]')];
  buttons.forEach(button=>button.onclick=async()=>{
    const old=pulse, i=Number(button.dataset.pulse), values=[[2,2,2,4],[3,3,3,3],[4,4,4,2],[5,5,5,1]][i];
    buttons.forEach(b=>b.disabled=true);
    try{pulse=await api('/mood',{method:'POST',body:JSON.stringify({day:istDay(),mood:values[0],energy:values[1],focus:values[2],stress:values[3],note:old?.note || null})});buttons.forEach(b=>{b.classList.toggle('selected',b===button);b.setAttribute('aria-pressed',String(b===button))});document.querySelector('#pulse-status').textContent='Saved for today.';document.querySelector('#note-form button[type=submit]').disabled=false;notify('Check-in saved.');}
    catch(error){notify(error.message)}finally{buttons.forEach(b=>b.disabled=false)}
  });
  const form=document.querySelector('#note-form'); if(!form)return;
  document.querySelector('#cancel-note').onclick=()=>document.querySelector('#pulse-note').value=pulse?.note || '';
  form.onsubmit=async event=>{event.preventDefault();if(!pulse)return;const button=form.querySelector('button[type=submit]');button.disabled=true;try{pulse=await api('/mood',{method:'POST',body:JSON.stringify({day:istDay(),mood:pulse.mood,energy:pulse.energy,focus:pulse.focus,stress:pulse.stress,note:document.querySelector('#pulse-note').value || null})});notify('Note saved.')}catch(error){notify(error.message)}finally{button.disabled=false}};
}

async function today(token){
  const result=await load({today:'/analytics/today',expenses:'/expenses?days=31',budgets:'/budgets',bills:'/statements',scores:'/analytics/scores?days=14',pulse:'/mood/'+istDay(),priority:'/priority?limit=100'});
  if(token!==generation)return;
  const budgets=result.budgets.data, bills=result.bills.data?latestBills(result.bills.data):null;
  const attention=[...(budgets || []).filter(b=>b.on_pace_to_exceed).map(b=>`${pretty(b.category)} is on pace to exceed its budget.`),...(bills || []).filter(b=>b.due_date && b.due_date<istDay()).map(b=>`${b.card_label} shows past due: ${money(Number(b.total_due))}.`)];
  main.innerHTML=heading('Today',new Intl.DateTimeFormat('en-IN',{weekday:'long',day:'numeric',month:'long',timeZone:'Asia/Kolkata'}).format(new Date()))+`<div id="brief">${briefHTML(result.today)}</div>`+(attention.length?`<section class="card"><h2>Needs you</h2>${attention.map(text=>`<p class="warning">${escape(text)}</p>`).join('')}<a href="#money">View budgets and bills</a></section>`:'')+pulseHTML(result.pulse)+`<div class="grid">${moneySummary(result.expenses)}${scoreHTML(result.scores)}</div><h2 class="section-title">Coming up</h2><section class="card">${bills?billsHTML(bills.filter(b=>b.due_date && b.due_date>=istDay()).slice(0,4)):blank('Bills could not be loaded.')}<div class="actions"><a href="#priority">${result.priority.data?result.priority.data.filter(p=>p.state==='needs_action').length+' Priority items need action':'View Priority'}</a><a href="#trips">Saved trips</a></div></section>`;
  wirePulse();
  if(result.today.data?.brief_status==='refreshing')pollBrief(token,0);
}
function pollBrief(token,attempt){poll=setTimeout(async()=>{if(token!==generation || document.hidden)return;try{const data=await api('/analytics/today');if(token!==generation)return;document.querySelector('#brief').innerHTML=briefHTML({data});if(data.brief_status==='refreshing' && attempt<5)pollBrief(token,attempt+1);}catch{}},20000);}

async function moneyPage(token){
  await mountMoney(main,{api,openDetail,notify,isCurrent:()=>token===generation});
}

async function priorityPage(token){
  const result=await load({items:'/priority?limit=100'});if(token!==generation)return;
  main.innerHTML=heading('Priority','Important mail · last 30 days')+(result.items.error?errorCard('Priority',result.items.error):result.items.data.map(item=>`<article class="card priority-card"><span class="tag">${escape(pretty(item.state))}</span><h2>${escape(item.subject)}</h2><p class="meta">${escape(item.sender)} · ${escape(date(item.received_at))}</p><p class="stack">${escape(item.summary)}</p>${item.action_text?`<p class="warning stack">${escape(item.action_text)}</p>`:''}${item.due_at?`<p class="meta stack">Deadline ${escape(timestamp(item.due_at))}</p>`:''}${item.event_start?`<p class="meta stack">Event ${escape(timestamp(item.event_start))}</p>`:''}<div class="actions"><button data-mail="${item.id}">Read message</button>${item.meeting_url?safeLink(item.meeting_url,'Join meeting'):safeLink(item.action_url,'Open action')}</div></article>`).join('') || `<section class="card">${blank('No active Priority items.')}</section>`);
  main.querySelectorAll('[data-mail]').forEach(button=>button.onclick=async()=>{button.disabled=true;openDetail('Message',blank('Loading the full message…'));try{const mail=await api('/priority/'+button.dataset.mail+'/message');openDetail(mail.subject,`<p class="meta">${escape(mail.sender)}</p><p class="email-body stack">${escape(mail.body)}</p>`);}catch(error){openDetail('Message',`<p class="error">${escape(error.message)}</p>`)}finally{button.disabled=false}});
}

function renderChat(){main.innerHTML=heading('Ask','Your LifeOS assistant')+`<p class="muted">Ask about your spending, health, plans, or what needs attention.</p><div id="chat-list" class="chat-list stack"></div><form id="ask-form" class="ask-form"><label class="meta" for="question">Your question<textarea id="question" rows="3" maxlength="8000" placeholder="How is this month’s spending looking?" required></textarea></label><button type="submit" ${chatBusy?'disabled':''}>${chatBusy?'Thinking…':'Send'}</button></form><p class="meta stack">Conversation stays in this open session.</p>`;const list=document.querySelector('#chat-list');for(const message of chatHistory)list.append(chatBubble(message));document.querySelector('#ask-form').onsubmit=sendChat;}
function chatBubble(message){const node=document.createElement('div');node.className='bubble '+message.role;const text=document.createElement('div');text.innerHTML=escape(message.content || message.status || 'Thinking…').replace(/\*\*([^*\n]+)\*\*/g,'<strong>$1</strong>');node.append(text);if(message.citations?.length){const sources=document.createElement('div');sources.className='sources';sources.textContent='Sources · '+message.citations.map(c=>(c.label || c.metric)+': '+c.value).join(' · ');node.append(sources);}if(message.incomplete){const warning=document.createElement('p');warning.className='warning stack';warning.textContent='This answer was interrupted. Please try again.';node.append(warning);}return node;}
async function sendChat(event){
  event.preventDefault();if(chatBusy)return;const field=document.querySelector('#question'), question=field.value.trim();if(!question)return;
  const previous=[];
  for(let i=0;i<chatHistory.length-1;i+=2){const user=chatHistory[i],reply=chatHistory[i+1];if(reply.content && !reply.incomplete)previous.push({role:'user',content:user.content},{role:'assistant',content:reply.content});}
  const history=previous.slice(-10);
  const user={role:'user',content:question}, answer={role:'assistant',content:'',status:'Thinking…'};chatHistory.push(user,answer);chatBusy=true;renderChat();
  function update(){if(page==='ask'){const list=document.querySelector('#chat-list');list.lastElementChild?.replaceWith(chatBubble(answer));}}
  try{
    const response=await fetch('/api/chat/stream',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:question,history,context_days:30})});
    if(!response.ok){let detail;try{detail=(await response.json()).detail}catch{}throw new Error(typeof detail==='string'?detail:'Ask is unavailable. Try again.');}
    const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',done=false;
    function consume(line){if(!line.trim())return;const data=JSON.parse(line);if(data.type==='delta')answer.content+=data.text || '';if(data.type==='status')answer.status=data.text;if(data.type==='error'){answer.status=data.text;answer.incomplete=true;}if(data.type==='done'){done=true;answer.citations=data.citations || [];answer.incomplete=Boolean(data.incomplete) || answer.incomplete;}}
    while(true){const chunk=await reader.read();buffer+=decoder.decode(chunk.value || new Uint8Array(),{stream:!chunk.done});const lines=buffer.split('\n');buffer=lines.pop();for(const line of lines)consume(line);update();if(chunk.done)break;}
    if(buffer.trim())consume(buffer);if(!done)answer.incomplete=true;
  }catch(error){answer.status=error.message;answer.incomplete=true;}finally{chatBusy=false;update();if(page==='ask'){const button=document.querySelector('#ask-form button');button.disabled=false;button.textContent='Send';}}
}

async function insightsPage(token){const result=await load({insights:'/analytics/synthesis',recap:'/analytics/weekly-recap'});if(token!==generation)return;main.innerHTML=heading('Insights','Patterns and your weekly recap',true)+(result.insights.error?errorCard('Insights',result.insights.error):result.insights.data.map(i=>`<section class="card"><p class="eyebrow">${escape(pretty(i.theme || 'Insight'))}</p><h2>${escape(i.title)}</h2><p>${escape(i.body)}</p>${i.action?`<p class="stack warning">${escape(i.action)}</p>`:''}${i.cites?.length?`<p class="meta stack">Sources · ${escape(i.cites.join(' · '))}</p>`:''}</section>`).join('') || `<section class="card">${blank('No insights available yet.')}</section>`)+(result.recap.data?`<h2 class="section-title">Your week</h2><section class="card"><p class="meta">${escape(date(result.recap.data.week_start))} – ${escape(date(result.recap.data.week_end))}</p><p class="email-body stack">${escape(result.recap.data.prose)}</p></section>`:result.recap.error?.status===404?'':errorCard('Weekly recap',result.recap.error));}

async function tripsPage(token){const result=await load({trips:'/trips'});if(token!==generation)return;main.innerHTML=heading('Saved trips','Last successful backups from your phone',true)+(result.trips.error?errorCard('Trips',result.trips.error):result.trips.data.map(t=>`<section class="card"><h2>${escape(t.title)}</h2><p class="meta">Backed up ${escape(timestamp(t.updated_at))} · Revision ${t.revision}</p><div class="actions"><button data-trip="${escape(t.id)}">View plan</button></div></section>`).join('') || `<section class="card">${blank('No trip backups found.')}</section>`);main.querySelectorAll('[data-trip]').forEach(button=>button.onclick=async()=>{button.disabled=true;openDetail('Trip',blank('Loading your saved plan…'));try{const data=await api('/trips/'+encodeURIComponent(button.dataset.trip));const payload=data.payload;openDetail(data.title,`<p class="meta">${escape(date(payload.trip?.start_date))} – ${escape(date(payload.trip?.end_date))}</p>${(payload.days || []).map(day=>`<section class="card stack"><p class="eyebrow">${escape(date(day.date))}</p><h2>${escape(day.title)}</h2>${(payload.activities || []).filter(a=>a.day_id===day.id).map(a=>`<div class="list-row"><div><h3>${escape(a.title)}</h3><p class="meta">${escape(pretty(a.status || 'Planned'))}</p>${a.notes?`<p>${escape(a.notes)}</p>`:''}</div></div>`).join('') || blank('No activities saved.')}</section>`).join('') || blank('No saved itinerary.')}`);}catch(error){openDetail('Trip',`<p class="error">${escape(error.message)}</p>`)}finally{button.disabled=false}});}
async function sourcesPage(token){const result=await load({status:'/status/sync'});if(token!==generation)return;const labels={bank_mail:'Bank mail',statements:'Card statements',priority_mail:'Priority mail',sleep_vitals:'Sleep & vitals',model:'Local AI'};main.innerHTML=heading('Data sources','Last successful updates',true)+(result.status.error?errorCard('Data sources',result.status.error):`<section class="card">${result.status.data.sources.map(s=>`<div class="list-row"><div><h3>${escape(labels[s.source] || pretty(s.source))}</h3><p class="meta">${escape(timestamp(s.last_success_at))}</p>${s.last_error?`<p class="error">${escape(s.last_error)}</p>`:''}</div><span class="${s.stale?'warning':'muted'}">${s.stale?'Stale':'Current'}</span></div>`).join('')}</section>`)+`<section class="card"><h2>Health on your iPhone</h2><p class="muted">Health Auto Export sends metrics and workouts to LifeOS. This web app reads the data already received.</p></section>`;}
function morePage(){main.innerHTML=heading('More')+`<a class="menu-link" href="#insights">Insights<small>Patterns, coaching, and your weekly recap</small></a><a class="menu-link" href="#trips">Saved trips<small>View plans backed up before the phone was wiped</small></a><a class="menu-link" href="#sources">Data sources<small>See when LifeOS last received each source</small></a><section class="card stack"><h2>Keep LifeOS on your Home Screen</h2><p class="muted">In Safari, open the Share menu and choose Add to Home Screen. Use this address while connected to your home Wi-Fi.</p></section>`;}

async function route(){
  clearTimeout(poll);const token=++generation;page=location.hash.slice(1) || 'today';if(!['today','money','priority','ask','more','insights','trips','sources'].includes(page))page='today';
  window.scrollTo(0,0);
  document.querySelectorAll('[data-page]').forEach(a=>{const active=a.dataset.page===page || a.dataset.page==='more' && ['insights','trips','sources'].includes(page);if(active)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');});
  document.title=(pretty(page))+' · LifeOS';main.innerHTML=heading(pretty(page))+blank('Loading…');
  try{if(page==='today')await today(token);else if(page==='money')await moneyPage(token);else if(page==='priority')await priorityPage(token);else if(page==='ask')renderChat();else if(page==='insights')await insightsPage(token);else if(page==='trips')await tripsPage(token);else if(page==='sources')await sourcesPage(token);else morePage();}catch(error){if(token===generation)main.innerHTML=heading(pretty(page))+errorCard('Could not open this page',error);}
}
route();

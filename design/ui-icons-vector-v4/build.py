from pathlib import Path
import json,base64
p=Path(__file__).parent;p.mkdir(exist_ok=True)
def path(d):return f'<path d="{d}"/>'
def rect(x,y,w,h,r=1.5):return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}"/>'
def circle(x,y,r):return f'<circle cx="{x}" cy="{y}" r="{r}"/>'
folder=path('M3 7V5.5A1.5 1.5 0 0 1 4.5 4H9l2 3h8.5A1.5 1.5 0 0 1 21 8.5v10a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5V7Z')
chat=path('M6 18l-3 3V5.5A1.5 1.5 0 0 1 4.5 4h15A1.5 1.5 0 0 1 21 5.5v11a1.5 1.5 0 0 1-1.5 1.5H6Z')
play=path('m10 8 6 4-6 4Z')
lines=path('M4 5h16M4 11h10M4 17h8')
refresh=path('M20 8a8 8 0 1 0 0 8M20 3v5h-5')
image=rect(3,4,18,16)+path('m4 17 5-5 4 4 3-3 5 5')+circle(16,9,1)
plus=path('M12 8v8M8 12h8')
icons=[
('project-folder','Проект',folder,''),
('project-add','Добавить проект',folder,plus),
('session-chat','Сессия',chat,''),
('session-running','Работающая сессия',chat,play),
('session-new','Новая сессия',chat,plus),
('session-archive','Неактивные',rect(3,4,18,4)+path('M5 8v12h14V8'),path('M9 12h6')),
('artifact-gallery','Результаты',path('M7 3h14v14M3 7h14v14H3Z'),path('m4 18 4-4 3 3 2-2 3 3')),
('session-actions','Действия сессии',circle(5,12,1)+circle(12,12,1)+circle(19,12,1),''),
('send-message','Отправить',path('m3 10 18-7-7 18-3-8-8-3Z'),path('m11 13 10-10')),
('stop-run','Остановить',rect(5,5,14,14,1),''),
('attach-file','Прикрепить',path('m9 15 7-7a2.1 2.1 0 0 0-3-3l-8 8a4.2 4.2 0 0 0 6 6l9-9a6.3 6.3 0 0 0-9-9'),''),
('preview-expand','Развернуть',path('M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5'),''),
('preview-collapse','Свернуть',path('M4 9h5V4M15 4v5h5M20 15h-5v5M9 20v-5H4'),''),
('artifact-reload','Обновить результат',refresh,''),
('preview-close','Закрыть просмотр',path('m6 6 12 12M18 6 6 18'),''),
('quota-gauge','Квота',path('M4 19a10 10 0 1 1 16 0'),path('m12 13 5-6M5 13h1M8 6l1 1M12 3v1M19 13h-1')),
('quota-refresh','Обновить квоту',path('M4 17a9 9 0 1 1 16-2M20 10v5h-5'),path('m11 12 4-5')),
('action-history','История действий',circle(9,9,6)+path('M15 16h6M9 21h12'),path('M9 5v4h3')),
('queue-resume','Продолжить очередь',lines,path('m17 12 5 4-5 4Z')),
('queue-expand','Раскрыть очередь',lines,path('M16 20h5v-5m-7 7 7-7')),
('queue-delete','Удалить из очереди',path('M3 6h18M6 6l1 15h10l1-15M9 6V3h6v3'),path('M10 10v7M14 10v7')),
('queue-up','Выше',path('M12 21V3m-6 6 6-6 6 6'),''),
('queue-down','Ниже',path('M12 3v18m-6-6 6 6 6-6'),''),
('queue-return-last','Вернуть фрагмент',path('M9 4 3 10l6 6M3 10h12a5 5 0 0 1 0 10h-3'),''),
('preview-fit','Вписать',path('M3 8V3h5M16 3h5v5M21 16v5h-5M8 21H3v-5'),rect(7,8,10,8,0.5)),
('preview-actual-size','Исходный размер',rect(4,4,16,16,0.5),path('M8 8h3v8M14 8h2v8')),
('model-select','Модель',rect(6,6,12,12,1),path('M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3')),
('reasoning-level','Усилие',path('M5 20v-5M12 20V9M19 20V3'),''),
('send-separate','Отправить отдельно',path('m3 9 14-6-5 14-3-5-6-3Z'),path('M18 14h3M18 18h3M18 22h3')),
('send-append','Дополнить',rect(3,3,13,14)+path('M7 21h14V7'),path('M7 10h5M9.5 7.5v5')),
('queue-pause','Пауза',path('M8 4v16M16 4v16'),''),
('session-rename','Переименовать',path('m4 16 12-12 4 4L8 20H4v-4Z'),path('m13 7 4 4')),
]
cards=[]
for i,(name,title,main,detail) in enumerate(icons):
 svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-linecap="round" stroke-linejoin="round"><g stroke-width="1.5">{main}</g><g stroke-width="1.15">{detail}</g></svg>'
 (p/(name+'.svg')).write_text(svg)
 src='data:image/svg+xml;base64,'+base64.b64encode(svg.encode()).decode()
 old=p.parent/'ui-icons-minimal-v3/alpha-sliced-v3'/f'icon-{i+1:03}.png'
 oldsrc='data:image/png;base64,'+base64.b64encode(old.read_bytes()).decode()
 cards.append(f'<article><h3>{title}</h3><div class="examples">'+''.join(f'<div><img width="{s}" height="{s}" src="{src}"><small>{s}px</small></div>' for s in [16,20,24,32]) +f'</div><footer>Было <img width="24" height="24" src="{oldsrc}"> → <img width="24" height="24" src="{src}"> SVG</footer></article>')
 html='''<!doctype html><meta charset="utf-8"><title>Точные SVG-иконки</title><style>body{background:#1d222b;color:#eef2f8;font:14px system-ui;margin:28px}h1{font-size:24px}p{color:#b4becd}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}article{background:#272e39;padding:18px;border-radius:8px}h3{font-size:13px;font-weight:500;margin:0 0 20px}.examples{display:flex;align-items:center;justify-content:space-between;height:70px}.examples div{text-align:center;min-width:40px}small{display:block;color:#94a2b8;margin-top:10px}footer{border-top:1px solid #3b4554;margin-top:16px;padding-top:12px;color:#94a2b8;display:flex;align-items:center;gap:12px}img{object-fit:contain}</style><h1>32 основные иконки · точная векторная геометрия</h1><p>Белые SVG. Основные линии 1,5 px, детали 1,15 px на сетке 24 × 24. Показаны реальные размеры без увеличения.</p><main>'''+''.join(cards)+'</main>'
(p/'index.html').write_text(html,encoding='utf-8')
print('32 SVG icons and comparison gallery written')

rem set DB_PASSWORD=DV_Cthdbc14@
rem set IMAN_ACCESS_TOKEN=sk-8e8be9398ad34062900766c732b5f72e
rem $env:IMAN_REFRESH_TOKEN = "долговременный_ключ"
rem C:\Java\jdk-17\bin\java.exe -Xmx6g -jar docx-to-xml-service-1.1.0.jar --spring.config.location=file:.\application.yml

rem POWERSHELL
rem $env:IMAN_ACCESS_TOKEN="sk-8e8be9398ad34062900766c732b5f72e"
rem $env:DB_PASSWORD="DV_Cthdbc14@"
rem C:\Java\jdk-17\bin\java.exe -Xmx6g -jar C:\Users\KvochkinAY\IdeaProjects\Spring\Project\convertDocxToXML\target\docx-to-xml-service-1.1.1.jar --spring.config.location=file:C:\Users\KvochkinAY\IdeaProjects\Spring\Project\convertDocxToXML\src\main\resources\application.yml

rem процессы  java из PowerShell
rem Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId,CommandLine

rem -------------------------------------------------------------------------------------------------
rem Критерий:
rem < 1% — конфиг выше финальный, оставляем 50 потоков, живём.
rem 1–3% — 50 уже на грани: после поднятия read-timeout до 5m większość уйдёт в успехи, но понаблюдайте неделю.
rem > 3% — снижайтесь до 40 и пишите админам письмом (оно у вас есть, без пункта 5): «какая загрузка инференса, какой лимит параллельности на наш токен, можно ли поднять».
rem
rem $t = (Select-String -Path C:/svc/docx-processor/logs/application.log -Pattern 'Request cancelled').Count
rem >> "таймаутов=$t из $($t+11909) => {0:P2}" -f ($t/($t+11909))
rem -------------------------------------------------------------------------------------------------
rem узнать потолок IMAN:max-tokens (powershell):
rem Select-String -Path C:/svc/docx-processor/logs/application.log -Pattern 'tokens prompt/completion: (\d+)/(\d+)' |  % { [int]$_.Matches[0].Groups[2].Value } | Sort-Object
rem -------------------------------------------------------------------------------------------------

rem ПРОМПТ:
rem 1. Выбери из текста название всех юр.лиц и контрагентов (Company, Counterparties), их ИНН и дополнительную информацию если присутствуют.
rem 2. Напиши Вид документа по классификации В системах электронного документооборота к которому он относится.
rem 3. Переведи текст без XML тегов.
rem 4. Выведи полученный текст к новой XML структуре с обязательным заполнение XML тегов.(
rem <Document>
rem <Header>
rem   <DocumentType>Договор оказания услуг</DocumentType>
rem   <RelatedDocument></RelatedDocument>
rem   <Date>02.07.2026</Date>
rem   <Number>0064/2026/ТЯГА</Number>
rem   <OrderDate></OrderDate>
rem   <OrderNumber></OrderNumber>
rem   <CopyNumber></CopyNumber>
rem   <Summa>3000 руб./час</Summa>
rem   <NDS>Дополнительно</NDS>
rem   <Currency>RUB</Currency>
rem </Header>
rem <Parties>
rem   <Company>
rem     <Country>Россия</Country>
rem     <Name>ООО «Агентство ТЯГА»</Name>
rem     <Role>Исполнитель</Role>
rem     <Address>603140, г. Нижний Новгород, Мотальный пер., д 10А, оф.302</Address>
rem     <INN>5258158263</INN>
rem     <Phones>
rem       <Phone></Phone>
rem     </Phones>
rem     <SignatoryCompany>Генеральный директор Поляшов А.Е.</SignatoryCompany>
rem   </Company>
rem   <Counterparties>
rem     <Counterparty>
rem       <Country>Россия</Country>
rem       <Name>ООО «Дистрибьютор легковых автомобилей»</Name>
rem       <Address>603028, г. Нижний Новгород, Комсомольское шоссе, д. 5 «А», помещение 7</Address>
rem       <INN>5257216748</INN>
rem       <Phones>
rem         <Phone></Phone>
rem       </Phones>
rem       <SignatoryCounterparty>Генеральный директор Петрунин Денис Евгеньевич</SignatoryCounterparty>
rem     </Counterparty>
rem   </Counterparties>
rem   </Parties>
rem </Document>
rem)


rem IMAN
rem Как работает Bearer для API
rem 1) Используйте access_token в заголовке Authorization: Bearer.
rem 2) Обновление идет через POST /auth/refresh с refresh_token integration key.
rem 3) Provider access/refresh хранятся только на сервере в Vault.
rem
rem access_token:
rem imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTAwOTI4NzQsImlhdCI6MTc5MDA3ODUzNSwianRpIjoiODhlYzBmNzUyNzYzNGU5ODhiNDQ2MzA2ODFlOTMxMzEiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6ImFjY2VzcyIsInR5cCI6ImludGVncmF0aW9uIiwidWlkIjo1NDF9.dce5249b36ec2232d822f52ed4d1395c27cf70b73f3bcfa558a273bfc6d74228
rem refresh_token:
rem imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTA2ODMzMzUsImlhdCI6MTc5MDA3ODUzNSwianRpIjoiYmEwY2I2ODRmMjZjNGUzYmFkZjFjYjBkYjI4Zjc4YmMiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6InJlZnJlc2giLCJ0eXAiOiJpbnRlZ3JhdGlvbiIsInVpZCI6NTQxfQ.827bc54428641b126e9859883e9bd4c1169a20ef0c909fba52ac2abbb5533a36
rem
rem
rem curl -sS -X POST "https://iman.ai.loodsen.ru/auth/refresh" \
rem -H "Content-Type: application/json" \
rem -d '{"refresh_token":"imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTA2ODMzMzUsImlhdCI6MTc5MDA3ODUzNSwianRpIjoiYmEwY2I2ODRmMjZjNGUzYmFkZjFjYjBkYjI4Zjc4YmMiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6InJlZnJlc2giLCJ0eXAiOiJpbnRlZ3JhdGlvbiIsInVpZCI6NTQxfQ.827bc54428641b126e9859883e9bd4c1169a20ef0c909fba52ac2abbb5533a36"}'
rem
rem curl -sS "https://iman.ai.loodsen.ru/api/v1/agents/list?my_agents=true"
rem -H "Authorization: Bearer imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTAwOTI4NzQsImlhdCI6MTc5MDA3ODUzNSwianRpIjoiODhlYzBmNzUyNzYzNGU5ODhiNDQ2MzA2ODFlOTMxMzEiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6ImFjY2VzcyIsInR5cCI6ImludGVncmF0aW9uIiwidWlkIjo1NDF9.dce5249b36ec2232d822f52ed4d1395c27cf70b73f3bcfa558a273bfc6d74228"

rem https://iman.ai.loodsen.ru/agents/example_react
rem {
rem   "jsonrpc": "2.0",
rem   "id": "11111111-1111-1111-1111-111111111111",
rem   "method": "message/send",
rem   "params": {
rem     "message": {
rem       "role": "user",
rem       "parts": [
rem         {
rem           "type": "text",
rem           "text":  "ПРОМТ!"
rem         }
rem       ],
rem       "messageId": "22222222-2222-2222-2222-222222222222",
rem       "contextId": "33333333-3333-3333-3333-333333333333"
rem     },
rem     "configuration": {
rem       "acceptedOutputModes": ["text"]
rem     },
rem     "skillId": "default"
rem   }
rem }


rem https://ai.loodsen.ru/api/v1/chat/completions
rem Authorization: Bearer
rem access_token: imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTA2ODQwMjMsImlhdCI6MTc5MDY1NTkzMCwianRpIjoiMTJmODY0ODk2NGNhNDdlODgwZjAyMmExM2M2ZWJmOGUiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6ImFjY2VzcyIsInR5cCI6ImludGVncmF0aW9uIiwidWlkIjo1NDF9.c15f2767fa28c954408c3c58ae78a7cc262cd5da646399cf59954728579c4a85
rem refresh_token: imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTEyNjA3MzAsImlhdCI6MTc5MDY1NTkzMCwianRpIjoiZWIzMzlkY2VhMjNjNDY0MjgxMmQ0YmUyZjA5MzQyOWYiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6InJlZnJlc2giLCJ0eXAiOiJpbnRlZ3JhdGlvbiIsInVpZCI6NTQxfQ.52ee8d17d363b3f2a6c4bb679a8c1369a4dc53342d572305b4773b9a08ade344
rem
rem curl -sS -X POST "https://iman.ai.loodsen.ru/auth/refresh" \
rem       -H "Content-Type: application/json" \
rem       -d '{"refresh_token":"imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTEyNjA3MzAsImlhdCI6MTc5MDY1NTkzMCwianRpIjoiZWIzMzlkY2VhMjNjNDY0MjgxMmQ0YmUyZjA5MzQyOWYiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6InJlZnJlc2giLCJ0eXAiOiJpbnRlZ3JhdGlvbiIsInVpZCI6NTQxfQ.52ee8d17d363b3f2a6c4bb679a8c1369a4dc53342d572305b4773b9a08ade344"}'
rem
rem curl -sS "https://iman.ai.loodsen.ru/api/v1/agents/list?my_agents=true" \
rem      -H "Authorization: Bearer imanv_tok_v1:eyJhdWQiOlsicGxhdGZvcm1fYXBpIl0sImNpZCI6ImRlZmF1bHQiLCJleHAiOjE3OTA2ODQwMjMsImlhdCI6MTc5MDY1NTkzMCwianRpIjoiMTJmODY0ODk2NGNhNDdlODgwZjAyMmExM2M2ZWJmOGUiLCJraW5kIjoicGxhdGZvcm0iLCJwcm92aWRlciI6ImtleWNsb2FrIiwic2lkIjoiZGVmYXVsdCIsInRva2VuX3VzZSI6ImFjY2VzcyIsInR5cCI6ImludGVncmF0aW9uIiwidWlkIjo1NDF9.c15f2767fa28c954408c3c58ae78a7cc262cd5da646399cf59954728579c4a85"
rem {
rem   "model": "Qwen/Qwen3.5-27B-FP8",
rem   "messages": [
rem     {
rem       "role": "system",
rem       "content": "Расскажи о том что спросит пользователь."
rem     },
rem     {
rem       "role": "user",
rem       "content": "красивые виды лесов"
rem     }
rem   ],
rem   "temperature": 0.1,
rem   "max_tokens": 10000,
rem   "stream": false
rem }

rem чтение лога
rem Select-String C:\svc\docx-processor\logs\application.log -Pattern 'ПЕРЕГРУЗКА'
rem Select-String C:\svc\docx-processor\logs\application.log -Pattern 'документ битый|poison'
rem Select-String C:\svc\docx-processor\logs\application.log -Pattern 'OCR-кластер' | Select-Object -Last 8

rem ## Снимок с момента рестарта + живой монитор
rem >> [Console]::OutputEncoding = [Text.Encoding]::UTF8
rem >> $log  = 'C:\svc\docx-processor\logs\application.log'
rem >> $tail = 'C:\svc\restart.log'
rem >> $t0   = [datetime]'2026-09-28 08:19'    # первая строка рестарта — 08:19:28
rem >>
rem >> # --- отрезать лог от момента рестарта (ИЗМЕНЕНО: защита от $null) ---
rem >> $st = Select-String -Path $log -Pattern 'Started .* in \d+|JVM running' | Select-Object -Last 1
rem >> if ($st) {
rem >>   "строка старта найдена, строка $($st.LineNumber)"
rem >>   Get-Content $log -Encoding UTF8 | Select-Object -Skip ($st.LineNumber - 1) |
rem >>     Set-Content $tail -Encoding UTF8
rem >> } else {
rem >>   "строки старта нет — лог уже начинается с рестарта, беру весь файл"
rem >>   Copy-Item $log $tail -Force
rem >> }
rem >>
rem >> # --- снимок ---
rem >> $succ = Select-String -Path $tail -Pattern 'ответ за (\d+) мс'
rem >> $lat  = $succ | ForEach-Object { [int]$_.Matches[0].Groups[1].Value }
rem >> $rej  = (Select-String -Path $tail -Pattern 'marked=true').Count
rem >> $to   = (Select-String -Path $tail -Pattern 'timed out|timeout').Count
rem >> $err  = (Select-String -Path $tail -Pattern 'ERROR|Exception').Count
rem >> $hrs  = ((Get-Date) - $t0).TotalHours
rem >> "прошло {0:n1} ч | успехов {1} (~{2:n0}/ч) | отбраковок {3} (~{4:n0}/ч) | таймаутов {5} | ошибок {6}" -f `
rem >>   $hrs, $lat.Count, ($lat.Count/$hrs), $rej, ($rej/$hrs), $to, $err
rem >> if ($lat.Count -ge 10) {
rem >>   $s = $lat | Sort-Object
rem >>   "латентность мс: медиана {0:n0} | p95 {1:n0} | max {2:n0}" -f `
rem >>     $s[[int]($s.Count*0.5)], $s[[int]($s.Count*0.95)], $s[-1]
rem >> }
rem >>
rem >> # --- темп по минутам (ИЗМЕНЕНО: последние 30 минут, чтобы не мельтешило) ---
rem >> $succ | ForEach-Object { ($_.Line -replace '.*(\d{2}:\d{2}):\d{2}.*','$1') } |
rem >>   Group-Object | Sort-Object Name | Select-Object -Last 30 |
rem >>   Format-Table Name, Count -AutoSize
rem >>
rem >> # --- живой монитор ---
rem >> $prevC = $null; $prevR = $null
rem >> while ($true) {
rem >>   $c = (Select-String -Path $log -Pattern 'ответ за'    | Measure-Object).Count
rem >>   $r = (Select-String -Path $log -Pattern 'marked=true' | Measure-Object).Count
rem >>   if ($null -eq $prevC) { "{0}  базовая линия: успехов {1}, отбраковок {2}" -f (Get-Date -Format HH:mm:ss), $c, $r }
rem >>   else { "{0}  +{1} отв/мин | +{2} отбрак/мин | всего успехов {3}, отбрак {4}" -f (Get-Date -Format HH:mm:ss), ($c-$prevC), ($r-$prevR), $c, $r }
rem >>   $prevC = $c; $prevR = $r; Start-Sleep 60
rem >> }


rem проверка доступности сервера CDV
rem Invoke-WebRequest -UseBasicParsing -Method Post `
rem >>   -Uri "http://doc-app7:4842/CDvService/86d8e4719c0cf1119f353a68ddb4ef9b/ToolsPage/ReadFile"
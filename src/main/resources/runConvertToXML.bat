set DB_PASSWORD DV_Cthdbc14@
C:\Java\jdk-17\bin\java.exe -Xmx6g -jar docx-to-xml-service-1.1.0.jar --spring.config.location=file:.\application.yml

rem процессы  java из PowerShell
rem Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId,CommandLine


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
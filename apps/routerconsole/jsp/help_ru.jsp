<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<html><head><title>Консоль маршрутизатора I2P - справка</title>
<%@include file="css.jsi" %>
</head><body>
<%@include file="summary.jsi" %>

<h1>Справка маршрутизатора I2P</h1>

<div class="main" id="main">

<p> Если Вы хотите помочь в улучшении или переводе документации, если у Вас есть идеи, как еще помочь проекту, пожалуйста, загляните в раздел документации 
<a href="http://www.i2p2.i2p/getinvolved.html">как стать участником</a>. </p>

<p>Дальнейшие инструкции доступны в <a href="http://www.i2p2.i2p/faq.html">FAQ на www.i2p2.i2p</a>

<br>Также, имеет смысл зайти на <a href="http://forum.i2p/">форум I2P</a> и IRC-каналы проекта.</p>

<h2>Описание статусной панели</h2>

<p>
Для большинства параметров на статусной панели можно <a href="configstats.jsp">настроить</a> построение <a href="graphs.jsp">графиков</a> в целях более подробного анализа. 
</p>

<h3>Общая информация</h3><ul>

<li class="tidylist"><b>Локальный идентификатор</b>

Первые 4 символа (24 бита) из Вашего 44-символьного (256-битного) Base64 хеша маршрутизатора. Полный хеш показывается на странице <a href="netdb.jsp?r=.">информации о маршрутизаторе</a>. Никогда никому не показывайте хеш своего маршрутизатора, так как в нем содержится информация о Вашем IP-адресе.


<li class="tidylist"><b>Версия:</b>

Версия Вашего I2P маршрутизатора. 

<li class="tidylist"><b>Время:</b>

Текущее время (UTC) и величина рассинхронизации времени (если есть). Для правильной работы I2P нужно точное системное время. Пожалуйста, поправьте системное время, если расхождение приближается к 1-ой минуте.

<li class="tidylist"><b>Доступность:</b>

Результат проверки Вашим маршрутизатором, насколько он открыт для входящих соединений от маршрутизаторов других пользователей. Подробнее смотрите на <a href="config.jsp#help">странице сетевых настроек</a>. 

</ul>

<h3>Пиры</h3><ul>

<li class="tidylist"><b>Активные:</b>

Первое число — это количество пиров, с которыми происходил обмен сообщениями за последние несколько минут. Значение может меняться от 8-10 до нескольких сотен в зависимости от Вашего общего трафика, доли транзитного трафика, локально создаваемого трафика. Второе число — это количество пиров, наблюдавшихся за последний час. Не волнуйтесь, если эти числа сильно меняются. Это нормально.  <a href="configstats.jsp#router.activePeers">[Включить построение графика]</a>. 

<li class="tidylist"><b>Быстрые:</b>

Количество пиров, которые используются Вашим маршрутизатором для построения клиентских туннелей. В общем случае это значение будет в диапазоне 8-30. Список быстрых пиров можно посмотреть на странице <a href="profiles.jsp">профили</a>. <a href="configstats.jsp#router.fastPeers">[Включить построение графика]</a>. 

<li class="tidylist"><b>Высокоёмкие:</b>

Количество пиров, которые используются Вашим маршрутизатором для построения части зондирующих туннелей. В общем случае это значение будет в диапазоне 8-75. Быстрые пиры входят в группу высокоёмких. Список высокоёмких пиров можно посмотреть на странице <a href="profiles.jsp">профили</a>. <a href="configstats.jsp#router.highCapacityPeers">[Включить построение графика]</a>. 

<li class="tidylist"><b>Хорошо интегрированные:</b>

Количество пиров, которые используются Вашим маршрутизатором для запросов к сетевой базе данных. Обычно в таком качестве выступают «floodfill» пиры. Список хорошо интегрированных пиров можно посмотреть в конце страницы <a href="profiles.jsp">профили</a>. 

<li class="tidylist"><b>Известные:</b>

Это общее количество пиров известных Вашему маршрутизатору. Их список показывается на странице <a href="netdb.jsp">обзор сетевой базы данных</a>. Это значение может варьироваться от десятков до тысяч. Значение не соответствует реальному полному размеру сети, так как маршрутизатору в сети I2P достаточно знать лишь часть других маршрутизаторов. Значение зависит от Вашего общего трафика, доли транзитного трафика, локально создаваемого трафика. 

</ul>

<h3>Трафик (входящий/исходящий)</h3>

<div align="justify">
Все значения показаны в байтах/секунду. Настроить ограничения трафика можно на странице <a href="config.jsp">сетевых настроек</a>.
Для трафика по умолчанию включено <a href="graphs.jsp">построение графиков</a>.</div>

<h3>Локальные туннели</h3>

<div align="justify">
Локальные приложения, выходящие в I2P сеть через Ваш маршрутизатор. Это могут быть клиенты, запущенные через <a href="i2ptunnel/index.jsp">менеджер туннелей</a>, внешние программы, подключающиеся через интерфейсы SAM, BOB или напрямую через I2CP.    
</div>

<h3>Туннели (входящие/исходящие)</h3>

<div align="justify">
Список туннелей можно посмотреть на странице <a href="tunnels.jsp">обзор туннелей</a>.</div>

<ul>

<li class="tidylist"><div align="justify"><b>Зондирующие:</b>
Туннели, созданные Вашим маршрутизатором для связи с floodfill-пирами, тестирования уже существующих туннелей и построения новых.</div> 

<li class="tidylist"><b>Клиентские:</b>
Туннели, созданные Вашим маршрутизатором для каждого локального клиента.

<li class="tidylist"><b>Транзитные:</b>

Туннели, построенные другими маршрутизаторами, проходящие через Ваш маршрутизатор. Их количество может сильно варьироваться в зависимости от потребностей сети, настроенной доли транзитного трафика и объема локально создаваемого трафика. Рекомендуемый способ ограничения количества транзитных туннелей — настроить долю транзитного трафика на странице <a href="config.jsp#help">сетевых настроек</a>. Также можно задать точный ограничитель количества через параметр <tt>router.maxParticipatingTunnels=nnn</tt> на странице <a href="configadvanced.jsp">дополнительных настроек</a>.

<a href="configstats.jsp#tunnel.participatingTunnels">[Включить построение графика]</a>. 

<li class="tidylist"><b>Доля транзита:</b>

Количество транзитных туннелей, проходящих через Ваш маршрутизатор, поделенное на суммарное количество хопов в Ваших зондирующих и клиентских туннелях. Значение больше 1.00 означает, что Вы предоставляете для сети больше туннелей, чем используете сами.

</ul>

<h3>Занятость</h3>

<div align="justify">Некоторые базовые индикаторы перегруженности маршрутизатора:</div>
<ul>

<li class="tidylist"><b>Задержка заданий:</b>

Как долго задания ожидают выполнения. Содержимое очереди можно посмотреть на странице <a href="jobs.jsp">очередь заданий</a>.  К сожалению, есть ещё несколько внутренних очередей, статус которых в консоли не показывается. Задержка заданий в нормальной ситуации должна быть нулевой. Если она систематически выше 500ms, то либо Ваш компьютер слишком медленный, либо с Вашим маршрутизатором проблемы. 

<a href="configstats.jsp#jobQueue.jobLag">[Включить построение графика]</a>.

<li class="tidylist"><b>Задержка сообщений:</b>

Как долго исходящие сообщения находятся в очереди. В нормальном случае эта задержка должна быть не выше нескольких сотен миллисекунд. Если она систематически выше 1000ms, то либо Ваш компьютер слишком медленный, либо Вам следует перенастроить ограничение скорости, либо локальные клиенты (чаще всего bittorrent) посылают слишком много данных. Для таких клиентов имеет смысл ограничить скорость. 

<a href="configstats.jsp#transport.sendProcessingTime">[Включить построение графика]</a> (transport.sendProcessingTime). 

<li class="tidylist"><b>Задержка туннелей:</b>

Время прохождения сигнала при проверке туннеля (сообщение посылается от клиентского туннеля до зондирующего или в обратном направлении). Это значение в нормальном случае должно быть ниже 5 секунд. Если оно систематически выше, то либо Ваш компьютер слишком медленный, либо Вам следует перенастроить ограничение скорости, либо с сетью что-то не в порядке.  

<a href="configstats.jsp#tunnel.testSuccessTime">[Включить построение графика]</a> (tunnel.testSuccessTime). 

<li class="tidylist"><b>Очередь запросов:</b>

Количество пока необработанных запросов от других маршрутизаторов о построении транзитных туннелей через Ваш маршрутизатор. В нормальном случае это значение должно быть около нуля. Если оно систематически выше, то Ваш компьютер слишком медленный и Вам следует настроить меньшую долю транзитного трафика.

<li class="tidylist"><b>Принимаем/Не принимаем туннели:</b>

Состояние Вашего маршрутизатора по приему или отклонению запросов от других маршрутизаторов о построении туннелей. Ваш маршрутизатор может принимать все запросы, принимать/отклонять часть запросов или отклонять все запросы, в зависимости от сетевой загрузки, нагрузки на процессор и необходимости резервировать полосу пропускания для локальных клиентов. 

</ul>

<h2>Лицензии</h2>

<p>Код I2P-маршрутизатора (router.jar) и его SDK (i2p.jar) находятся в общественном достоянии с некоторыми исключениями:</p>

<ul>
<li class="tidylist">Код для алгоритмов ElGamal и DSA — под лицензией BSD, автор: TheCrypto</li>
<li class="tidylist">Код для алгоритмов SHA256 и HMAC-SHA256 — под лицензией MIT, автор: Legion из Bouncycastle</li>
<li class="tidylist">Код для алгоритма AES — под лицензией Cryptix (MIT), авторы: Cryptix team</li>
<li class="tidylist">Код для SNTP — под лицензией BSD, автор: Adam Buckley</li>
<li class="tidylist">Всё остальное полностью в общественном достоянии, авторы: jrandom, mihi, hypercubus, oOo, ugha, duck, shendaras, и другие.</li>
</ul>

<p>Поверх I2P маршрутизатора работают различные приложения-клиенты, каждое со своим набором лицензий и зависимостей. Например, эта страница входит в приложение консоли маршрутизатора, которое сделано из усеченной версии <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a> (в сборку не включены демонстрационные приложения и прочие дополнения, настройки упрощены). Jetty позволяет запускать в составе маршрутизатора стандартные JSP/сервлеты. Jetty использует javax.servlet.jar разработанный в составе проекта Apache (http://www.apache.org/). 
</p>

<p>Ещё одно приложение на этой странице — <a href="http://www.i2p2.i2p/i2ptunnel">I2PTunnel</a> (а тут <a href="i2ptunnel/" target="_blank">его вебинтерфейс</a>).  Автор mihi, лицензия GPL. I2PTunnel занимается туннелированнием обычного TCP/IP трафика через I2P (может применяться для eepproxy и irc-прокси). <a href="http://susi.i2p/">susimail</a> — почтовый клиент с <a href="susimail/susimail">вебинтерфейсом</a>, автор susi23, лицензия  GPL. Адресная книга помогает управлять содержимым Ваших hosts.txt файлов (подробнее см. ./addressbook/), автор <a href="http://ragnarok.i2p/">Ragnarok</a>.</p> 

<p>В поставку маршрутизатора включен <a href="http://www.i2p2.i2p/sam">SAM</a> интерфейс, автор human, приложение в общественном достоянии. SAM предназначен для использования приложениями-клиентами, такими как <a href="http://duck.i2p/i2p-bt/">bittorrent-клиенты</a>. Маршрутизатором используется оптимизированная под разные PC-архитектуры библиотека для вычислений с большими числами – jbigi, которая в свою очередь использует библиотеку <a href="http://swox.com/gmp/">GMP</a> (LGPL лицензия). Вспомогательные приложения для Windows созданы с использованием <a href="http://launch4j.sourceforge.net/">Launch4J</a>, а инсталлятор собран при помощи <a href="http://www.izforge.com/izpack/">IzPack</a>. Подробнее о других доступных приложениях и их лицензиях смотрите на странице <a href="http://www.i2p2.i2p/licenses">I2P Software Licenses</a>. Исходный код I2P маршрутизатора и идущих в комплекте приложений можно найти на нашей <a href="http://www.i2p2.i2p/download">странице загрузки</a>. </p> 


<h2>История версий</h2>

 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="256" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />

 <p>Более подробный список изменений можно найти в файле history.txt, который находится в директории установки I2P.
 </p><hr></div></body></html>

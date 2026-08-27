<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ include file="fragments/head.jspf" %>
<%@ include file="fragments/shell-start.jspf" %>
<section class="page-heading"><div><span class="eyebrow">LIVE</span><h1>실시간 채팅</h1><p>Spring MVC의 Server-Sent Events 스트림을 확인하세요.</p></div><span class="connection-badge" id="connection-state"><i></i>연결 중</span></section>
<section class="panel chat-panel"><div class="chat-feed" id="chat-feed"><div class="loading-panel"><span class="spinner"></span></div></div><form class="chat-compose" id="chat-form"><label><span class="sr-only">메시지</span><input name="text" maxlength="500" autocomplete="off" placeholder="메시지를 입력하세요" required></label><button class="primary-button" type="submit">전송</button></form><p class="form-error chat-error" id="chat-error" hidden></p></section>
<script type="module" src="${pageContext.request.contextPath}/assets/js/pages/chat.js"></script>
<%@ include file="fragments/shell-end.jspf" %>

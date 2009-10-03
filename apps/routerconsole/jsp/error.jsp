<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    // Let's make this easy...
    final Integer ERROR_CODE = (Integer) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_STATUS_CODE);
    final String ERROR_URI = (String) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_REQUEST_URI);
    final String ERROR_MESSAGE = (String) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_MESSAGE);
    if (ERROR_CODE != null && ERROR_MESSAGE != null) {
        // this is deprecated but we don't want sendError()
        response.setStatus(ERROR_CODE.intValue(), ERROR_MESSAGE);
    }
    // If it can't find the iframe or viewtheme.jsp I wonder if the whole thing blows up...
%>
<html><head><title>I2P Router Console - Page Not Found</title>
<%@include file="css.jsp" %>
</head><body>
<%
if (System.getProperty("router.consoleNonce") == null) {
    System.setProperty("router.consoleNonce", new java.util.Random().nextLong() + "");
}
%>
<%@include file="summary.jsp" %>
<h1><%=ERROR_CODE%> <%=ERROR_MESSAGE%></h1>
<div class="sorry" id="warning">
Sorry! You appear to be requesting a non-existent Router Console page or resource.<hr>
Error 404: <%=ERROR_URI%> not found.
</div></body></html>
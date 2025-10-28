package org.joget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackAttachment;
import net.gpedro.integrations.slack.SlackMessage;
import org.joget.apps.app.lib.UserNotificationAuditTrail;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.AuditTrail;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.PluginThread;
import org.joget.commons.util.StringUtil;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONObject;

public class SlackNotification extends UserNotificationAuditTrail {
    private final static String MESSAGE_PATH = "message/SlackNotification";
    
    private SlackApi api = null;
    
    @Override
    public String getName() {
        return "Slack Notification";
    }

    @Override
    public String getVersion() {
        return "8.0.1";
    }
    
    @Override
    public String getClassName() {
        return getClass().getName();
    }
    
    @Override
    public String getLabel() {
        //support i18n
        return AppPluginUtil.getMessage("org.joget.SlackNotification.pluginLabel", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getDescription() {
        //support i18n
        return AppPluginUtil.getMessage("org.joget.SlackNotification.pluginDesc", getClassName(), MESSAGE_PATH);
    }
    
    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/slackNotification.json", null, true, MESSAGE_PATH);
    }

    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        boolean isAdmin = WorkflowUtil.isCurrentUserInRole(WorkflowUserManager.ROLE_ADMIN);
        if (!isAdmin) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        
        String action = request.getParameter("action");
        if ("sendTestMessage".equals(action)) {
            String message = "";
            try {
                AppDefinition appDef = AppUtil.getCurrentAppDefinition();
                
                String apiurl = AppUtil.processHashVariable(request.getParameter("apiurl"), null, null, null, appDef);
                String url = AppUtil.processHashVariable(request.getParameter("url"), null, null, null, appDef);
                String testChannel = AppUtil.processHashVariable(request.getParameter("testChannel"), null, null, null, appDef);
                if(testChannel.equals("")){
                    testChannel = AppPluginUtil.getMessage("SlackNotification.testMessage", getClassName(), MESSAGE_PATH);
                }

                setProperty("apiurl", apiurl);
                setProperty("url", url);
                setProperty("text", testChannel);
                
                if (testChannel != null && !testChannel.isEmpty()) {
                    sendMessage(testChannel, null);
                } else {
                    sendMessage(null, null);
                }
                
                message = AppPluginUtil.getMessage("SlackNotification.sendTestMessage.success", getClassName(), MESSAGE_PATH);
            } catch (Exception e) {
                LogUtil.error(this.getClassName(), e, "Fail to send Test Message to Slack");
                message = AppPluginUtil.getMessage("SlackNotification.sendTestMessage.fail", getClassName(), MESSAGE_PATH) + "\n" +  StringUtil.escapeString(e.getMessage(), StringUtil.TYPE_HTML);
            }
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.accumulate("message", message);
                jsonObject.write(response.getWriter());
            } catch (Exception e) {
                //ignore
            }
        } else {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
    
    @Override
    protected void sendEmail (final Map props, final AuditTrail auditTrail, final WorkflowManager workflowManager, final List<String> users, final WorkflowActivity wfActivity) {
        new PluginThread(new Runnable() {

            public void run() {
                WorkflowUserManager workflowUserManager = (WorkflowUserManager) AppUtil.getApplicationContext().getBean("workflowUserManager");

                String base = (String) props.get("base");
                String url = (String) props.get("url");
                String urlName = (String) props.get("urlName");
                String parameterName = (String) props.get("parameterName");
                String passoverMethod = (String) props.get("passoverMethod");
                String text = (String) props.get("text");
                String linkLabel = AppPluginUtil.getMessage("SlackNotification.viewAssignment", getClassName(), MESSAGE_PATH);

                String activityInstanceId = wfActivity.getId();
                String link = getLink(base, url, passoverMethod, parameterName, activityInstanceId);

                if (!link.startsWith("http")) {
                    if (!link.startsWith("/")) {
                        link = "/" + link;
                    }
                    link = base + link;
                }
                
                SlackMessage message = createMessage();

                try {
                    for (String username : users) {
                        workflowUserManager.setCurrentThreadUser(username);
                        WorkflowAssignment wfAssignment = null;

                        int count = 0;
                        do {
                            wfAssignment = workflowManager.getAssignment(activityInstanceId);

                            if (wfAssignment == null) {
                                Thread.sleep(4000); //wait for assignment creation
                            }
                            count++;
                        } while (wfAssignment == null && count < 5); // try max 5 times

                        if (wfAssignment != null) {
                            String channel = getSlackUsername(username, wfAssignment);
                            if (channel != null && !channel.isEmpty()) {
                                message.setText(AppUtil.processHashVariable(text, wfAssignment, null, null));

                                message.setAttachments(new ArrayList<SlackAttachment>());
                                SlackAttachment attachment = new SlackAttachment();
                                attachment.setFallback(link);
                                if (urlName != null && !urlName.isEmpty()) {
                                    attachment.setTitle(AppUtil.processHashVariable(urlName, wfAssignment, null, null));
                                } else {
                                    attachment.setTitle(linkLabel);
                                }
                                attachment.setTitleLink(link);
                                message.addAttachments(attachment);
                                try {
                                    LogUtil.info(SlackNotification.class.getName(), "Sending slack message to " + username);
                                    sendMessage(channel, message);
                                    LogUtil.info(SlackNotification.class.getName(), "Sending slack message completed to " + username);
                                } catch (Exception ex) {
                                    LogUtil.error(UserNotificationAuditTrail.class.getName(), ex, "Error sending slack message");
                                }
                            }
                        } else {
                            LogUtil.debug(UserNotificationAuditTrail.class.getName(), "Fail to retrieve assignment for " + username);
                        }
                    }
                } catch (Exception e) {
                    LogUtil.error(UserNotificationAuditTrail.class.getName(), e, "Error executing plugin");
                }
            }
        }).start();
    }
    
    protected SlackApi getApi() {
        if (api == null) {
            api = new SlackApi(getPropertyString("apiurl"));
        }
        return api;
    }
    
    protected String getSlackUsername(String username, WorkflowAssignment assignment) {
        String syntax = getPropertyString("usernameTransform");
        syntax = syntax.replaceAll(StringUtil.escapeRegex("{username}"), StringUtil.escapeRegex(username));
        return AppUtil.processHashVariable(syntax, assignment, null, null);
    }
    
    protected void sendMessage(String channel, SlackMessage message) {
        if (message == null) {
            message = createMessage();
        }
        if (channel != null && !channel.isEmpty()) {
            message.setChannel(channel);
        }
        
        getApi().call(message);
    }
    
    protected SlackMessage createMessage() {
        SlackMessage message = new SlackMessage(getPropertyString("text"));
        
        String username = getPropertyString("username");
        if (!username.isEmpty()) {
            message.setUsername(username);
        }
        
        String customIcon = getPropertyString("customIcon");
        if (!customIcon.isEmpty()) {
            if ("joget".equals(customIcon)) {
                HttpServletRequest request = WorkflowUtil.getHttpServletRequest();
                if (request != null) {
                    String url = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath() + "/images/v3/logo.png";
                    message.setIcon(url);
                }
            } else if ("url".equals(customIcon)) {
                message.setIcon(getPropertyString("iconUrl"));
            } else {
                message.setIcon(getPropertyString("iconEmoji"));
            }
        }
        
        message.setUnfurlLinks("true".equalsIgnoreCase(getPropertyString("unfurl_links")));
        message.setUnfurlMedia("true".equalsIgnoreCase(getPropertyString("unfurl_media")));
        
        return message;
    }
}

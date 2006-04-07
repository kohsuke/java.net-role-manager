package org.kohsuke.javanet.devrolehandler;

import dalma.ReplyIterator;
import dalma.Workflow;
import dalma.endpoints.email.EmailEndPoint;
import dalma.endpoints.email.MimeMessageEx;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.kohsuke.jnt.JavaNet;
import org.kohsuke.jnt.ProcessingException;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.logging.Level;

/**
 * A workflow that follows up with the requester.
 *
 */
public class ConversationImpl extends Workflow {
    private final String projectName;
    private final String role;
    private final String userName;

    private final EmailEndPoint endpoint;

    public ConversationImpl(EmailEndPoint endpoint, String projectName, String role, String userName) {
        this.projectName = projectName;
        this.role = role;
        this.userName = userName;
        this.endpoint = endpoint;
    }

    /**
     * Decides what to do with this role request.
     *
     * @return
     *      The e-mail to be sent out, or null to exit immediately.
     */
    private String decideAction() throws Exception {
        setTitle(role+" role request from "+userName+" to "+projectName);

        // read the policy file
        Document policyDoc = getPolicyDocument();

        String mailContent = null;

        // determine what to do with this role
        getLogger().info("Determining the rule");
        for( Element rule : (List<Element>)policyDoc.getRootElement().elements("rule") ) {
            String roleAtt = rule.attributeValue("role", "");
            String[] roles = roleAtt.split(",");

            for (String role : roles) {
                role = role.trim();
                if(role.equals(this.role)) {
                    // matching rule found
                    String action = rule.attributeValue("action","").toLowerCase();
                    if(action.equals("approve")) {
                        approve();
                        return null;
                    }
                    if(action.equals("deny")) {
                        deny(replace(new StringReader(rule.getText())).trim());
                        return null;
                    }
                    if(action.equals("talk")) {
                        mailContent = replace(new StringReader(rule.getText())).trim();
                        break;
                    }
                    // non recognizable action
                    getLogger().severe("Unknown action "+action);
                    return null;
                }
            }
        }
        if(mailContent==null) {
            getLogger().severe("No matching rule found");
            return null;
        }

        return mailContent;
    }

    @Override
    public void run() {
        try {
            String mailContent = decideAction();
            if(mailContent==null)
                return;

            MimeMessageEx msg = new MimeMessageEx(endpoint.getSession(),
                new ByteArrayInputStream(mailContent.getBytes("UTF-8")));
            msg.setHeader("X-Role-Manager","http://role-manager.dev.java.net/");

            // expire in one week
            Calendar cal = new GregorianCalendar();
            cal.add(Calendar.DATE,7);

            getLogger().info("Starting a conversation. Expire date is "+cal.getTime().toGMTString());

            // send a clarification message and wait for the owner to eventually respond

            boolean noreply = true;

            ReplyIterator<MimeMessageEx> itr = endpoint.waitForMultipleReplies(msg, cal.getTime());
            while(itr.hasNext()) {
                noreply = false;
                MimeMessageEx reply = itr.next();
                try {
                    String body = reply.getMainContent();
                    BufferedReader r = new BufferedReader(new StringReader(body));
                    String line;
                    while((line=r.readLine())!=null) {
                        if(line.equals("##APPROVE")) {
                            recordAction(reply, "Approving a request based on e-mail from "+reply.getFrom());
                            approve();
                            return;
                        }
                        if(line.equals("##DENY")) {
                            recordAction(reply, "Denying a request based on e-mail from "+reply.getFrom());
                            deny(body);
                            return;
                        }
                    }
                } catch (MessagingException e) {
                    getLogger().log(Level.WARNING, "Failed to parse a reply",e);
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "Failed to parse a reply",e);
                }
            }

            if(noreply) {
                // time out reached. cancel the request
                getLogger().info("No response in one week. Denying a request.");
                deny(
                    "It's been a week, but we haven't heard anything from you, " +
                    "so I'm going ahead and denying the request. Please write " +
                    "to us if you are still interested in joining the project");
            } else {
                // TODO: allow timeout to be extended
                getLogger().info("Exiting due to a timeout");
            }

        } catch (Exception e) {
            getLogger().log(Level.SEVERE,e.getMessage(),e);
            notifyError(e);
        }
    }

    private void notifyError(Exception e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            MimeMessageEx msg = endpoint.createTextMessage(
                new InternetAddress("owner@" + projectName + ".dev.java.net"),
                sw.toString());
            msg.setSubject("Role processing failed for "+userName);
            endpoint.send(msg);
        } catch (MessagingException x) {
            getLogger().log(Level.SEVERE,x.getMessage(),x);
        }
    }

    /**
     * Fetches the policy document.
     */
    private Document getPolicyDocument() throws MalformedURLException, DocumentException {
        URL policyFile = new URL("https://" + projectName + ".dev.java.net/role-approval.policy");

        while(true) {
            getLogger().info("Fetching the policy file: "+policyFile);

            Document policyDoc = new SAXReader().read(policyFile);
            Element root = policyDoc.getRootElement();

            if(root.getName().equals("redirect")) {
                getLogger().info("Redirected to "+root.getTextTrim());
                policyFile = new URL(policyFile,root.getTextTrim());
                continue;
            }

            return policyDoc;
        }
    }

    private void recordAction(MimeMessageEx reply, String msg) throws MessagingException {
        getLogger().info(msg);
        MimeMessageEx report = reply.reply(true);
        report.setText(msg);
        endpoint.send(report);
    }

    /**
     * Denies a request
     */
    private void deny(String msg) throws ProcessingException {
        getLogger().info("Denying request");
        JavaNet jn = JavaNet.connect();

        jn.getProject(projectName).getMembership().declineRole(
            jn.getUser(userName), role, msg );
    }

    /**
     * Approves a request.
     */
    private void approve() throws ProcessingException {
        getLogger().info("Approving request");
        JavaNet jn = JavaNet.connect();

        jn.getProject(projectName).getMembership().grantRole(
            jn.getUser(userName), role
        );
    }

    /**
     * Replace keywords in the input stream.
     *
     * <p>
     * The current implementation doesn't handle encoding correctly.
     */
    private String replace(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        StringWriter sw = new StringWriter();
        PrintWriter w = new PrintWriter(sw);

        String line;

        while((line=r.readLine())!=null) {
            line = line.replace("${project}",projectName);
            line = line.replace("${role}",role);
            line = line.replace("${user}",userName);
            w.println(line);
        }
        r.close();
        w.close();
        return sw.toString();
    }
}

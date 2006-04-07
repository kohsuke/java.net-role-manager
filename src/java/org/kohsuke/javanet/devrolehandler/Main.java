package org.kohsuke.javanet.devrolehandler;

import dalma.Description;
import dalma.Engine;
import dalma.Program;
import dalma.Resource;
import dalma.endpoints.email.EmailEndPoint;
import dalma.endpoints.email.NewMailHandler;

import javax.mail.internet.MimeMessage;

import org.kohsuke.jnt.tools.RoleRequest;
import org.kohsuke.jnt.JavaNet;
import org.kohsuke.jnt.ProcessingException;

import java.io.InputStreamReader;
import java.io.File;

@Description("This application waits for a developer role request, and sends out an e-mail asking for clarifications")
public class Main extends Program {
    @Resource(description="used to receive role request e-mails and send out clarification e-mails")
    public EmailEndPoint email;

    @Resource(description="path to .java.net file")
    public String javaNetFile;

    public JavaNet connectJavaNet() throws ProcessingException {
        return JavaNet.connect(new File(javaNetFile));
    }

    public void init(final Engine engine) throws Exception {
        email.setNewMailHandler(new NewMailHandler() {
            public void onNewMail(MimeMessage mail) throws Exception {
                // technically, we need to get encoding from the e-mail,
                // but we know java.net uses UTF-8.
                RoleRequest rr = new RoleRequest(
                    new InputStreamReader(mail.getInputStream(), "UTF-8"));

                getLogger().info("Started a conversation with "+rr);
                engine.createConversation(new ConversationImpl(
                    Main.this, email, rr.projectName, rr.roleName, rr.userName ));
            }
        });
    }

    @Override
    public void main(Engine engine) throws Exception {
        getLogger().info("Started");
    }
}

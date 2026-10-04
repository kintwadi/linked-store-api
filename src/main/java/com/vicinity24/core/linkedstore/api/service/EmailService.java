package com.vicinity24.core.linkedstore.api.service;

import com.vicinity24.core.linkedstore.api.dto.MailContactRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {
    
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    
    private final JavaMailSender mailSender;
    
    @Value("${spring.mail.from:noreply@vicinity24.com}")
    private String fromEmail;
    
    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    
    public void sendPasswordResetEmail(String toEmail, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Password Reset Code - Vicinity24");
            
            String htmlContent = buildPasswordResetEmail(code);
            helper.setText(htmlContent, true);
            
            mailSender.send(message);
            logger.info("Password reset email sent to: {}", toEmail);
            
        } catch (MessagingException | MailException e) {
            logger.error("Failed to send password reset email to: {}", toEmail, e);
        }
    }
    
    public void sendSubscriptionVerificationEmail(String toEmail, String recipientName, String code, String planType, String language) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(buildSubscriptionVerificationSubject(planType, language));
            
            String htmlContent = buildSubscriptionVerificationEmail(recipientName, code, planType, language);
            helper.setText(htmlContent, true);
            
            mailSender.send(message);
            logger.info("Subscription verification email sent to: {}", toEmail);
            
        } catch (MessagingException | MailException e) {
            logger.error("Failed to send subscription verification email to: {}", toEmail, e);
        }
    }

    public void sendSignupEmailVerificationEmail(String toEmail, String recipientName, String code, String language) {
        String subject = buildSignupVerificationSubject(language);
        String htmlContent = buildSignupVerificationEmail(recipientName, code, language);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            logger.info("Signup email verification email sent to: {}", toEmail);
        } catch (MessagingException | MailException e) {
            logger.error("Failed to send signup email verification email to: {}", toEmail, e);
        }
    }

    public void sendReturnRatingEmail(String toEmail, String recipientName, String otherPartyName, String listingTitle, String listingReference, String ratingLink) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Rate your recent handover - Vicinity24");

            String htmlContent = buildReturnRatingEmail(recipientName, otherPartyName, listingTitle, listingReference, ratingLink);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            logger.info("Return rating email sent to: {}", toEmail);
        } catch (MessagingException | MailException e) {
            logger.error("Failed to send return rating email to: {}", toEmail, e);
        }
    }

    public void sendPickupReadyEmail(String toEmail, String recipientName, String listingTitle, String pickupLocation, String itemReference) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Ready for pickup - Vicinity24");

            String htmlContent = buildPickupReadyEmail(recipientName, listingTitle, pickupLocation, itemReference);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            logger.info("Pickup ready email sent to: {}", toEmail);
        } catch (MessagingException | MailException e) {
            logger.error("Failed to send pickup ready email to: {}", toEmail, e);
        }
    }

    public boolean sendContactInquiryEmail(String toEmail, MailContactRequest request) {
        if (request == null) {
            logger.warn("Contact inquiry email skipped because request was null");
            return false;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String senderEmail = request.getEmail() != null ? request.getEmail().trim() : "";
            String senderName = request.getFullName() != null ? request.getFullName().trim() : "";
            String recipient = toEmail != null && !toEmail.isBlank() ? toEmail.trim() : fromEmail;

            helper.setFrom(fromEmail);
            helper.setTo(recipient);
            if (!senderEmail.isBlank()) {
                helper.setReplyTo(senderEmail);
            }
            helper.setSubject(buildContactInquirySubject(request));
            helper.setText(buildContactInquiryEmail(request), true);

            mailSender.send(message);
            logger.info("Contact inquiry email sent from {} <{}> to {}", senderName, senderEmail, recipient);
            return true;
        } catch (MessagingException | MailException e) {
            logger.error("Failed to send contact inquiry email", e);
            return false;
        }
    }

    public boolean sendContactSales(MailContactRequest request, String contactSalesToEmail) {
        String to = contactSalesToEmail != null && !contactSalesToEmail.isBlank() ? contactSalesToEmail : fromEmail;
        return sendContactInquiryEmail(to, request);
    }
    
    private String buildPasswordResetEmail(String code) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #4F46E5; color: white; padding: 20px; text-align: center; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 5px; }
                    .code { 
                        font-size: 32px; 
                        font-weight: bold; 
                        letter-spacing: 5px; 
                        text-align: center; 
                        margin: 20px 0; 
                        color: #4F46E5;
                    }
                    .footer { margin-top: 20px; font-size: 12px; color: #666; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h2>Vicinity24 Password Reset</h2>
                    </div>
                    <div class="content">
                        <p>Hello,</p>
                        <p>You requested to reset your password. Use the verification code below to proceed:</p>
                        
                        <div class="code">%s</div>
                        
                        <p>This code will expire in 15 minutes for security reasons.</p>
                        
                        <p>If you didn't request this reset, please ignore this email or contact support if you have concerns.</p>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(code, buildStandardFooter());
    }

    private String buildPickupReadyEmail(String recipientName, String listingTitle, String pickupLocation, String itemReference) {
        String safeRecipient = recipientName != null && !recipientName.isBlank() ? recipientName.trim() : "there";
        String safeTitle = listingTitle != null && !listingTitle.isBlank() ? listingTitle.trim() : "your item";
        String safeLocation = pickupLocation != null ? pickupLocation.trim() : "";
        String safeItemReference = itemReference != null ? itemReference.trim() : "";
        String locationLine = safeLocation.isEmpty() ? "" : ("<p><b>Pickup location:</b> " + escapeHtml(safeLocation) + "</p>");
        String itemReferenceLine = safeItemReference.isEmpty() ? "" : ("<p><b>Return item reference:</b> <span style=\"font-family: monospace;\">" + escapeHtml(safeItemReference) + "</span></p>");
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #4F46E5; color: white; padding: 20px; text-align: center; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 5px; }
                    .title { font-size: 18px; font-weight: bold; margin: 0 0 10px 0; }
                    .footer { margin-top: 20px; font-size: 12px; color: #666; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h2>Vicinity24</h2>
                    </div>
                    <div class="content">
                        <p>Hello %s,</p>
                        <p class="title">%s is ready for pickup.</p>
                        %s
                        %s
                        <p>You can coordinate the pickup directly in the app chat.</p>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(escapeHtml(safeRecipient), escapeHtml(safeTitle), locationLine, itemReferenceLine, buildStandardFooter());
    }

    private String buildContactInquirySubject(MailContactRequest request) {
        String solution = effectivePlanLabel(request);
        String company = request.getCompany() != null && !request.getCompany().isBlank()
                ? request.getCompany().trim()
                : "Individual inquiry";
        String sender = request.getFullName() != null && !request.getFullName().isBlank()
                ? request.getFullName().trim()
                : "";
        if (sender.isEmpty()) {
            return "New contact request - " + solution + " - " + company;
        }
        return "New contact request - " + solution + " - " + sender + " - " + company;
    }

    private static String effectivePlanLabel(MailContactRequest request) {
        String code = firstNonBlank(
                request == null ? null : request.getPlanCode(),
                request == null ? null : request.getSolution()
        );
        if (code == null) return "General inquiry";
        String up = code.toUpperCase();
        if (up.contains("CUSTOM")) return "Custom Plan / Enterprise";
        if (up.contains("PRO")) return "Pro Plan";
        if (up.contains("PLUS")) return "Plus Plan";
        return code;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return null;
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return null;
    }

    private String buildContactInquiryEmail(MailContactRequest request) {
        String fullName = request.getFullName() != null && !request.getFullName().isBlank() ? request.getFullName().trim() : "Unknown";
        String email = request.getEmail() != null ? request.getEmail().trim() : "";
        String phone = request.getPhone() != null ? request.getPhone().trim() : "";
        String company = request.getCompany() != null && !request.getCompany().isBlank() ? request.getCompany().trim() : "—";
        String planLabel = effectivePlanLabel(request);
        String body = request.getMessage() != null ? request.getMessage().trim() : "";

        String phoneRow = "";
        if (!phone.isEmpty()) {
            phoneRow = "<tr><td>Phone</td><td>" + escapeHtml(phone) + "</td></tr>";
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 680px; margin: 0 auto; padding: 20px; }
                    .header { background: #0f172a; color: white; padding: 20px; text-align: center; }
                    .content { background: #f8fafc; padding: 28px; border-radius: 8px; }
                    .meta { width: 100%%; border-collapse: collapse; margin-bottom: 22px; }
                    .meta td { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; vertical-align: top; }
                    .meta td:first-child { width: 180px; font-weight: bold; color: #0f172a; }
                    .message-box { background: white; border: 1px solid #e2e8f0; border-radius: 8px; padding: 18px; white-space: pre-wrap; }
                    .reply-note { margin-top: 18px; font-size: 13px; color: #475569; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h2>New Contact Request · Linked-Store</h2>
                    </div>
                    <div class="content">
                        <p>A new contact request was submitted from the Linked-Store contact form.</p>
                        <table class="meta">
                            <tr><td>Full name</td><td>%s</td></tr>
                            <tr><td>Work email</td><td>%s</td></tr>
                            %s
                            <tr><td>Company</td><td>%s</td></tr>
                            <tr><td>Plan of interest</td><td>%s</td></tr>
                        </table>
                        <p><strong>Message</strong></p>
                        <div class="message-box">%s</div>
                        <p class="reply-note">Reply to this message to respond to the submitter directly (Reply-To is set to their email).</p>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(
                escapeHtml(fullName),
                escapeHtml(email),
                phoneRow,
                escapeHtml(company),
                escapeHtml(planLabel),
                escapeHtml(body),
                buildStandardFooter()
            );
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
    
    private String buildSubscriptionVerificationSubject(String planType, String language) {
        String lang = language != null ? language.toLowerCase() : "en";
        String plan = planType != null ? planType.toLowerCase() : "plus";
        if ("pt".equals(lang)) {
            return switch (plan) {
                case "starter" -> "Verifique seu e-mail para o Vicinity24 Starter";
                case "pro" -> "Verifique seu e-mail para o Vicinity24 Pro";
                default -> "Confirme seu teste do Vicinity24 Plus";
            };
        }
        if ("de".equals(lang)) {
            return switch (plan) {
                case "starter" -> "Bestätige deine E-Mail für Vicinity24 Starter";
                case "pro" -> "Bestätige deine E-Mail für Vicinity24 Pro";
                default -> "Bestätige deinen Vicinity24 Plus-Test";
            };
        }
        return switch (plan) {
            case "starter" -> "Verify your email for Vicinity24 Starter";
            case "pro" -> "Verify your email for Vicinity24 Pro";
            default -> "Confirm your Vicinity24 Plus trial";
        };
    }

    private String buildSubscriptionVerificationEmail(String recipientName, String code, String planType, String language) {
        String safeRecipient = recipientName != null && !recipientName.isBlank() ? recipientName.trim() : "there";
        String lang = language != null ? language.toLowerCase() : "en";
        String plan = planType != null ? planType.toLowerCase() : "plus";
        String headerTitle;
        String greeting;
        String intro;
        String validity;
        String ignore;

        if ("pt".equals(lang)) {
            headerTitle = switch (plan) {
                case "starter" -> "Verifique seu e-mail para o Vicinity24 Starter";
                case "pro" -> "Verifique seu e-mail para o Vicinity24 Pro";
                default -> "Verifique seu e-mail para o Vicinity24 Plus";
            };
            greeting = "Olá %s,".formatted(safeRecipient);
            intro = switch (plan) {
                case "starter" -> "Para ativar o plano Starter, insira o código de verificação abaixo no app:";
                case "pro" -> "Para ativar o plano Pro, insira o código de verificação abaixo no app:";
                default -> "Para iniciar o teste de 14 dias do plano Plus, insira o código de verificação abaixo no app:";
            };
            validity = "Este código é válido por pouco tempo por motivos de segurança.";
            ignore = "Se você não solicitou isso, pode ignorar este e-mail.";
        } else if ("de".equals(lang)) {
            headerTitle = switch (plan) {
                case "starter" -> "Bestätige deine E-Mail für Vicinity24 Starter";
                case "pro" -> "Bestätige deine E-Mail für Vicinity24 Pro";
                default -> "Bestätige deine E-Mail für Vicinity24 Plus";
            };
            greeting = "Hallo %s,".formatted(safeRecipient);
            intro = switch (plan) {
                case "starter" -> "Um deinen Starter-Plan zu aktivieren, gib den Bestätigungscode unten in der App ein:";
                case "pro" -> "Um deinen Pro-Plan zu aktivieren, gib den Bestätigungscode unten in der App ein:";
                default -> "Um deinen 14-tägigen Plus-Test zu starten, gib den Bestätigungscode unten in der App ein:";
            };
            validity = "Dieser Code ist aus Sicherheitsgründen nur für kurze Zeit gültig.";
            ignore = "Wenn du das nicht angefordert hast, kannst du diese E-Mail ignorieren.";
        } else {
            headerTitle = switch (plan) {
                case "starter" -> "Verify your email for Vicinity24 Starter";
                case "pro" -> "Verify your email for Vicinity24 Pro";
                default -> "Verify your email for Vicinity24 Plus";
            };
            greeting = "Hello %s,".formatted(safeRecipient);
            intro = switch (plan) {
                case "starter" -> "To activate your Starter plan, enter the verification code below in the app:";
                case "pro" -> "To activate your Pro plan, enter the verification code below in the app:";
                default -> "To start your 14-day Vicinity24 Plus trial, enter the verification code below in the app:";
            };
            validity = "This code is valid for a short time for security reasons.";
            ignore = "If you did not request this, you can ignore this email.";
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #0F766E; color: white; padding: 20px; text-align: center; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 5px; }
                    .code { 
                        font-size: 32px; 
                        font-weight: bold; 
                        letter-spacing: 5px; 
                        text-align: center; 
                        margin: 20px 0; 
                        color: #0F766E;
                    }
                    .footer { margin-top: 20px; font-size: 12px; color: #666; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h2>%s</h2>
                    </div>
                    <div class="content">
                        <p>%s</p>
                        <p>%s</p>
                        
                        <div class="code">%s</div>
                        
                        <p>%s</p>
                        
                        <p>%s</p>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(headerTitle, greeting, intro, code, validity, ignore, buildStandardFooter());
    }

    private String buildSignupVerificationSubject(String language) {
        String lang = language != null ? language.toLowerCase() : "en";
        if ("pt".equals(lang)) return "Confirme o seu e-mail - Vicinity24";
        if ("de".equals(lang)) return "Bestätige deine E-Mail - Vicinity24";
        return "Verify your email - Vicinity24";
    }

    private String buildSignupVerificationEmail(String recipientName, String code, String language) {
        String safeRecipient = recipientName != null && !recipientName.isBlank() ? recipientName.trim() : "there";
        String lang = language != null ? language.toLowerCase() : "en";
        String headerTitle;
        String greeting;
        String intro;
        String validity;
        String ignore;

        if ("pt".equals(lang)) {
            headerTitle = "Confirme o seu e-mail";
            greeting = "Olá %s,".formatted(safeRecipient);
            intro = "Para concluir o registo, introduza o código de verificação abaixo na aplicação:";
            validity = "Este código é válido por pouco tempo por motivos de segurança.";
            ignore = "Se não solicitou isto, pode ignorar este e-mail.";
        } else if ("de".equals(lang)) {
            headerTitle = "Bestätige deine E-Mail";
            greeting = "Hallo %s,".formatted(safeRecipient);
            intro = "Um die Registrierung abzuschließen, gib den Bestätigungscode unten in der App ein:";
            validity = "Dieser Code ist aus Sicherheitsgründen nur für kurze Zeit gültig.";
            ignore = "Wenn du das nicht angefordert hast, kannst du diese E-Mail ignorieren.";
        } else {
            headerTitle = "Verify your email";
            greeting = "Hello %s,".formatted(safeRecipient);
            intro = "To complete your sign up, enter the verification code below in the app:";
            validity = "This code is valid for a short time for security reasons.";
            ignore = "If you did not request this, you can ignore this email.";
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #111827; color: white; padding: 20px; text-align: center; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 5px; }
                    .code {
                        font-size: 32px;
                        font-weight: bold;
                        letter-spacing: 6px;
                        text-align: center;
                        margin: 20px 0;
                        color: #111827;
                    }
                    .footer { margin-top: 20px; font-size: 12px; color: #666; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h2>%s</h2>
                    </div>
                    <div class="content">
                        <p>%s</p>
                        <p>%s</p>
                        <div class="code">%s</div>
                        <p>%s</p>
                        <p>%s</p>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(headerTitle, greeting, intro, code, validity, ignore, buildStandardFooter());
    }

    private String buildReturnRatingEmail(String recipientName, String otherPartyName, String listingTitle, String listingReference, String ratingLink) {
        String safeRecipient = recipientName != null && !recipientName.isBlank() ? recipientName : "there";
        String safeOther = otherPartyName != null && !otherPartyName.isBlank() ? otherPartyName : "your neighbor";
        String safeTitle = listingTitle != null && !listingTitle.isBlank() ? listingTitle : "your listing";
        String safeReference = listingReference != null && !listingReference.isBlank() ? listingReference.trim() : "n/a";
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 640px; margin: 0 auto; padding: 20px; }
                    .header { background: #111827; color: white; padding: 18px 20px; text-align: left; border-radius: 10px 10px 0 0; }
                    .content { background: #f9fafb; padding: 22px 20px; border-radius: 0 0 10px 10px; border: 1px solid #e5e7eb; border-top: 0; }
                    .btn { display: inline-block; background: #2563eb; color: white !important; padding: 12px 16px; border-radius: 10px; text-decoration: none; font-weight: bold; }
                    .meta { font-size: 12px; color: #6b7280; margin-top: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h2 style="margin:0;">Thanks for using Vicinity24</h2>
                    </div>
                    <div class="content">
                        <p>Hi %s,</p>
                        <p>Your return for <strong>%s</strong> has been completed.</p>
                        <p>Please take a moment to rate <strong>%s</strong> based on your experience.</p>
                        <p class="meta">Item reference: %s</p>
                        <p class="meta">This secure link already identifies the completed handover, so you can leave your rating without signing in.</p>
                        <p><a class="btn" href="%s" target="_blank" rel="noopener noreferrer">Rate now</a></p>
                        <p class="meta">This link is personal to your account. If you did not complete this handover, you can ignore this email.</p>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(safeRecipient, safeTitle, safeOther, safeReference, ratingLink, buildStandardFooter());
    }

    private String buildStandardFooter() {
        return """
            <div class="footer">
                <p>This is an automated message. Please do not reply to this email.</p>
                <p>Team Vicinity24</p>
                <p><a href="https://vicinity24.com" target="_blank" rel="noopener noreferrer">vicinity24.com</a> · <a href="mailto:info@vicinity24.com">info@vicinity24.com</a></p>
            </div>
            """;
    }
}

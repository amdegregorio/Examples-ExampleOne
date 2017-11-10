import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;

public class GmailExtractor {
   private static String OUTPUT_DIR = "/Users/AMD/Work/ExampleOne/Output/";
   private Gmail gmailService = null;
  
   public GmailExtractor(Gmail gmailService) {
	  this.gmailService = gmailService;
   }
  
   public int processMessages() throws IOException {
	  int numMessages = 0;
      // "me" is special way of using currently logged in user
      String userId = "me";
      
      //Follows rules from here: https://support.google.com/mail/answer/7190?hl=en
      String query = "label:BlogExample";

      //Retrieves all the messages using the query string
      ListMessagesResponse response = gmailService.users().messages().list(userId).setQ(query).execute();
      
      //The messages are returned a page at a time
      //Page through and load all the messages in one collection
      List<Message> messages = new ArrayList<Message>();
      while (response.getMessages() != null) {
         messages.addAll(response.getMessages());
         if (response.getNextPageToken() != null) {
            String pageToken = response.getNextPageToken();
            response = gmailService.users().messages().list(userId).setQ(query).setPageToken(pageToken).execute();
         } else {
            break;
         }
      }
      
      numMessages = messages.size();
      
      for (Message message : messages) {
         message = gmailService.users().messages().get(userId, message.getId()).setFormat("FULL").execute();

         MessagePart messagePart = message.getPayload();
         String messageContent = "";
         String subject = "";

         if (messagePart != null) {
            List<MessagePartHeader> headers = messagePart.getHeaders();
            for (MessagePartHeader header : headers) {
               //find the subject header.
               if (header.getName().equals("Subject")) {
                  subject = header.getValue().trim();
                  break;
               }
            }
         }
          
         //Create a subdirectory and file name from the subject
         //Parse the header to remove characters that can't be in a file path name
         String subdirName = subject.replaceAll("/", "-").replaceAll("<","-").replaceAll(">","-").replaceAll(":","-").replaceAll("\\\\","-").replaceAll("\\|","-").replaceAll("\\?","").replaceAll("\\*","-").replaceAll("\"","-").trim();
         String emailFileName = subdirName +".txt";
         
         File subDir = new File(OUTPUT_DIR + subdirName);
         if (!subDir.exists()) {
        	 subDir.mkdirs();
         }
         
         messageContent = getContent(message);
         
         try {
            //Create a text file for the raw data
            File emailTextFile = new File(subDir, emailFileName);
         
            if (emailTextFile.exists() || emailTextFile.createNewFile()) {
               BufferedWriter bw = new BufferedWriter(new FileWriter(emailTextFile));
               bw.write(subject);
               bw.newLine();
               bw.newLine();
               bw.write(messageContent);
               bw.flush();
               bw.close();
            }
         } catch (IOException ioe) {
           ioe.printStackTrace();
         }
         List<String> filenames = new ArrayList<String>();
         getAttachments(message.getPayload().getParts(), filenames, OUTPUT_DIR + subdirName, userId);
      }
      
      return numMessages;
   }
   
   /**
    * Extracts the contents from the message provided.
    * 
    * @param message an email message
    * @return a string containing the body content of the email
    */
   private String getContent(Message message) {
      StringBuilder stringBuilder = new StringBuilder();
      try {
         getPlainTextFromMessageParts(message.getPayload().getParts(), stringBuilder);
         if (stringBuilder.length() == 0) {
            stringBuilder.append(message.getPayload().getBody().getData());
         }
         byte[] bodyBytes = Base64.decodeBase64(stringBuilder.toString());
         String text = new String(bodyBytes, "UTF-8");
         return text;
      } catch (UnsupportedEncodingException e) {
         System.out.println("UnsupportedEncoding: " + e.toString());
         return message.getSnippet();
      }
   }

   /**
    * Retrieves the attachments from the email message provided and writes them to the output dir
    * 
    * @param service
    * @param message
    * @param fileNames
    * @param dir
    */
   private void getAttachments(List<MessagePart> messageParts, List<String> fileNames, String dir, String userId) {
      if (!dir.endsWith("/")) {
         dir += "/";
      }
      
      if (messageParts != null) {
         for (MessagePart part : messageParts) {
            //For each part, see if it has a file name, if it does it's an attachment
            if ((part.getFilename() != null && part.getFilename().length() > 0)) {
               String filename = part.getFilename();
               String attId = part.getBody().getAttachmentId();
               MessagePartBody attachPart;
               FileOutputStream fileOutFile = null;
               try {
                  //Go get the attachment part and get the bytes
                  attachPart = gmailService.users().messages().attachments().get(userId, part.getPartId(), attId).execute();
                  byte[] fileByteArray = Base64.decodeBase64(attachPart.getData());
                  
                  //Write the attachment to the output dir
                  fileOutFile = new FileOutputStream(dir + filename);
                  fileOutFile.write(fileByteArray);
                  fileOutFile.close();
                  fileNames.add(filename);
               } catch (IOException e) {
                  System.out.println("IO Exception processing attachment: " + filename);
               } finally {
                  if (fileOutFile != null) {
                     try {
                        fileOutFile.close();
                     } catch (IOException e) {
                        // probably doesn't matter
                     }
                  }
               }
            } else if (part.getMimeType().equals("multipart/related")) {
               if (part.getParts() != null) {
                  getAttachments(part.getParts(), fileNames, dir, userId);
               }
            }
         }
      }
   }
   
   /**
    * Compiles all of the message parts into on message
    * 
    * @param messageParts
    * @param stringBuilder
    */
   private void getPlainTextFromMessageParts(List<MessagePart> messageParts, StringBuilder stringBuilder) {
      if (messageParts != null) {
         
    	  for (MessagePart messagePart : messageParts) {
            if (messagePart.getMimeType().equals("text/plain")) {
               stringBuilder.append(messagePart.getBody().getData());
            } 

            if (messagePart.getParts() != null) {
               getPlainTextFromMessageParts(messagePart.getParts(), stringBuilder);
            }
         }
      }
   }
}

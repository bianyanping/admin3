package tech.wetech.admin3.ocrApi;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DumpCredit {
  public static void main(String[] args) throws Exception {
    File pdf = new File(args.length > 0 ? args[0] : "src/main/resources/CREDIT/credit.pdf");
    String pwd = args.length > 1 ? args[1] : "002836";

    try {
      PDDocument doc = PDDocument.load(pdf); // 无密码试
      System.out.println("=== OPENED WITHOUT PASSWORD, pages=" + doc.getNumberOfPages() + " ===");
      dumpPages(doc);
      doc.close();
    } catch (Exception e) {
      System.out.println("No-password open FAILED: " + e.getMessage());
      try {
        PDDocument doc = PDDocument.load(pdf, pwd);
        System.out.println("=== OPENED WITH PASSWORD '" + pwd + "', pages=" + doc.getNumberOfPages() + " ===");
        dumpPages(doc);
        doc.close();
      } catch (Exception e2) {
        System.out.println("With-password ALSO FAILED: " + e2.getMessage());
      }
    }
  }

  static void dumpPages(PDDocument doc) throws Exception {
    PDFTextStripper s = new PDFTextStripper();
    s.setSortByPosition(true);
    int total = doc.getNumberOfPages();
    StringBuilder all = new StringBuilder();
    for (int p = 1; p <= total; p++) {
      s.setStartPage(p); s.setEndPage(p);
      all.append("\n=== PAGE ").append(p).append(" ===\n");
      all.append(s.getText(doc));
    }
    java.nio.file.Files.writeString(
      java.nio.file.Path.of("target/credit_dump.txt"),
      all.toString(),
      java.nio.charset.StandardCharsets.UTF_8);
    System.out.println("WROTE target/credit_dump.txt, " + total + " pages, " + all.length() + " chars");
  }
}

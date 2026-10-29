package com.healthclaim.casedesk.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel / PDF export of the case list.
 * Besides the case details it shows, for the HOSPITAL part and the PATIENT part separately:
 * whether the part is assigned, who has it, the investigator's response (Yes / No / Pending) and the reason for "No".
 * All other parts (nominee, witness, police station, location, medical shop) are summarised in one column.
 */
@Service
public class ExportService {

    private static final String[] BASE_KEYS = {"tmcNumber", "patientName", "insuranceCompany", "claimTypeLabel",
            "patientDistrict", "patientState", "village", "hospitalName", "hospitalDistrict", "hospitalState",
            "policeStationDistrict", "policeStationState", "doa", "dod"};
    private static final String[] BASE_HEAD = {"TMCID", "Patient", "Insurance", "Claim type",
            "Patient district", "Patient state", "Patient village", "Hospital", "Hospital district", "Hospital state",
            "Police station district", "Police station state", "DOA", "DOD"};

    private static final String[] XLSX_HEADERS = concat(BASE_HEAD, new String[]{
            "Hospital part - Assigned", "Hospital part - Investigator", "Hospital part - Response", "Hospital part - Reason (if No) / note",
            "MRD charges bill available", "MRD charges amount",
            "Patient part - Assigned", "Patient part - Investigator", "Patient part - Response", "Patient part - Reason (if No) / note",
            "Other parts (assigned / response / reason)", "Part transfer", "Status", "Remarks?", "Assigned on"});

    // ================================================================ Excel

    public byte[] xlsx(List<Map<String, Object>> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Cases");
            CellStyle head = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font f = wb.createFont();
            f.setBold(true);
            head.setFont(f);
            head.setWrapText(true);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle yes = fill(wb, IndexedColors.LIGHT_GREEN);
            CellStyle no = fill(wb, IndexedColors.ROSE);
            CellStyle pending = fill(wb, IndexedColors.LIGHT_YELLOW);
            CellStyle notAssigned = fill(wb, IndexedColors.GREY_25_PERCENT);

            Row hr = sh.createRow(0);
            for (int i = 0; i < XLSX_HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(XLSX_HEADERS[i]);
                c.setCellStyle(head);
            }
            int r = 1;
            for (Map<String, Object> m : rows) {
                Row row = sh.createRow(r++);
                int col = 0;
                for (String k : BASE_KEYS) row.createCell(col++).setCellValue(safe(cell(m, k)));
                for (String type : new String[]{"HOSPITAL", "PATIENT"}) {
                    Map<String, Object> p = part(m, type);
                    row.createCell(col++).setCellValue(p == null ? "No" : "Yes");
                    row.createCell(col++).setCellValue(safe(p == null ? "" : str(p.get("investigatorName"))));
                    Cell resp = row.createCell(col++);
                    String rs = response(p);
                    resp.setCellValue(rs);
                    resp.setCellStyle(rs.startsWith("Yes") ? yes : rs.equals("No") ? no : rs.equals("Pending") ? pending : notAssigned);
                    Cell why = row.createCell(col++);
                    why.setCellValue(safe(reason(p)));
                    why.setCellStyle(wrap);
                    if (type.equals("HOSPITAL")) {
                        row.createCell(col++).setCellValue(safe(mrdBillAvailable(p, m)));
                        row.createCell(col++).setCellValue(safe(mrdBillAmount(p, m)));
                    }
                }
                Cell other = row.createCell(col++);
                other.setCellValue(safe(otherParts(m, "\n")));
                other.setCellStyle(wrap);
                row.createCell(col++).setCellValue(safe(transfers(m)));
                row.createCell(col++).setCellValue(safe(cell(m, "statusLabel")));
                row.createCell(col++).setCellValue(cell(m, "flagged"));
                row.createCell(col++).setCellValue(cell(m, "createdAt"));
            }
            for (int i = 0; i < XLSX_HEADERS.length; i++) {
                sh.autoSizeColumn(i);
                if (sh.getColumnWidth(i) > 50 * 256) sh.setColumnWidth(i, 50 * 256);
            }
            sh.createFreezePane(2, 1);
            sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(rows.size(), 1), 0, XLSX_HEADERS.length - 1));
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build the Excel file.");
        }
    }

    private static CellStyle fill(Workbook wb, IndexedColors color) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(color.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    // ================================================================ PDF

    public byte[] pdf(List<Map<String, Object>> rows, String title) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 20, 20, 26, 26);
            PdfWriter.getInstance(doc, out);
            doc.open();
            Font h = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 6.5f);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6.5f);
            Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, Font.NORMAL, Color.WHITE);
            Font green = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6.5f, Font.NORMAL, new Color(21, 128, 61));
            Font red = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6.5f, Font.NORMAL, new Color(185, 28, 28));
            Font amber = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6.5f, Font.NORMAL, new Color(180, 83, 9));
            Font grey = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 6.5f, Font.NORMAL, new Color(110, 110, 110));

            doc.add(new Paragraph("Health Claim - " + title, h));
            doc.add(new Paragraph("Generated " + LocalDate.now() + "  |  " + rows.size() + " case(s)", small));
            doc.add(new Paragraph(" ", small));

            String[] heads = {"TMCID / Assigned", "Patient", "Insurance / Claim type", "Districts", "Hospital",
                    "HOSPITAL PART", "PATIENT PART", "Other parts", "Status"};
            PdfPTable t = new PdfPTable(heads.length);
            t.setWidthPercentage(100);
            t.setWidths(new float[]{8, 9, 10, 10, 9, 17, 17, 14, 8});
            t.setHeaderRows(1);
            for (String hd : heads) {
                PdfPCell c = new PdfPCell(new Phrase(hd, head));
                c.setBackgroundColor(new Color(15, 36, 54));
                c.setHorizontalAlignment(Element.ALIGN_LEFT);
                t.addCell(c);
            }
            for (Map<String, Object> m : rows) {
                t.addCell(plain(cell(m, "tmcNumber") + "\n" + cell(m, "createdAt"), small));
                t.addCell(plain(cell(m, "patientName") + "\n" + cell(m, "village"), small));
                t.addCell(plain(cell(m, "insuranceCompany") + "\n" + cell(m, "claimTypeLabel"), small));
                t.addCell(plain("Patient: " + cell(m, "patientDistrict") + "\nHospital: " + cell(m, "hospitalDistrict"), small));
                t.addCell(plain(cell(m, "hospitalName") + "\nDOA " + cell(m, "doa") + "\nDOD " + cell(m, "dod"), small));
                Map<String, Object> hospitalPart = part(m, "HOSPITAL");
                t.addCell(partCell(hospitalPart, small, bold, green, red, amber, grey,
                        mrdBillAvailable(hospitalPart, m), mrdBillAmount(hospitalPart, m)));
                t.addCell(partCell(part(m, "PATIENT"), small, bold, green, red, amber, grey, "", ""));
                String tr = transfers(m);
                t.addCell(plain(otherParts(m, "\n") + (tr.isEmpty() ? "" : "\nTransfer: " + tr), small));
                t.addCell(plain(cell(m, "statusLabel") + (Boolean.TRUE.equals(m.get("flagged")) ? "\n(remarks)" : ""), small));
            }
            doc.add(t);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build the PDF.");
        }
    }

    private static PdfPCell plain(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setPadding(3);
        return c;
    }

    /** Assigned / investigator / response (coloured) / reason, one block per part. */
    private static PdfPCell partCell(Map<String, Object> p, Font small, Font bold, Font green, Font red, Font amber, Font grey,
                                     String mrdAvailable, String mrdAmount) {
        Phrase ph = new Phrase();
        if (p == null) {
            ph.add(new Chunk("Assigned: ", bold));
            ph.add(new Chunk("No\n", red));
            ph.add(new Chunk("(not part of this claim)", grey));
        } else {
            ph.add(new Chunk("Assigned: ", bold));
            ph.add(new Chunk("Yes\n", green));
            ph.add(new Chunk("Investigator: ", bold));
            ph.add(new Chunk(str(p.get("investigatorName")) + "\n", small));
            String rs = response(p);
            ph.add(new Chunk("Response: ", bold));
            ph.add(new Chunk(rs, rs.startsWith("Yes") ? green : rs.equals("No") ? red : amber));
            String why = reason(p);
            if (!why.isEmpty()) {
                ph.add(new Chunk("\n" + ("N".equals(p.get("answer")) ? "Reason: " : "Note: "), bold));
                ph.add(new Chunk(why, small));
            }
            if (!mrdAvailable.isEmpty()) {
                ph.add(new Chunk("\nMRD bill available: ", bold));
                ph.add(new Chunk(mrdAvailable, small));
                if (!mrdAmount.isEmpty()) {
                    ph.add(new Chunk("\nMRD charges amount: ", bold));
                    ph.add(new Chunk(mrdAmount, small));
                }
            }
        }
        PdfPCell c = new PdfPCell(ph);
        c.setPadding(3);
        return c;
    }

    // ================================================================ shared helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(Map<String, Object> m, String type) {
        Object o = m.get("parts");
        if (!(o instanceof List<?> list)) return null;
        for (Object x : list) {
            Map<String, Object> p = (Map<String, Object>) x;
            if (type.equals(p.get("type"))) return p;
        }
        return null;
    }

    /** Yes / No / Pending, or "Not assigned" when the claim has no such part. */
    private static String response(Map<String, Object> p) {
        if (p == null) return "Not assigned";
        Object a = p.get("answer");
        if ("Y".equals(a)) return Boolean.TRUE.equals(p.get("viaOther")) ? "Yes (via another person)" : "Yes";
        if ("N".equals(a)) return "No";
        return "Pending";
    }

    /** The reason for a "No", or the note for documents arriving through another person. */
    private static String reason(Map<String, Object> p) {
        if (p == null) return "";
        if ("N".equals(p.get("answer"))) return str(p.get("reason"));
        if (Boolean.TRUE.equals(p.get("viaOther"))) return str(p.get("note"));
        return "";
    }

    private static String mrdBillAvailable(Map<String, Object> p, Map<String, Object> row) {
        if ("CASHLESS".equals(row.get("claimType"))) return "Not applicable";
        if (p == null || p.get("mrdBillAvailable") == null) return "";
        return Boolean.TRUE.equals(p.get("mrdBillAvailable")) ? "Yes" : "No";
    }

    private static String mrdBillAmount(Map<String, Object> p, Map<String, Object> row) {
        if ("CASHLESS".equals(row.get("claimType"))) return "Not applicable";
        return p == null || p.get("mrdBillAmount") == null ? "" : str(p.get("mrdBillAmount"));
    }

    private static String otherParts(Map<String, Object> m, String sep) {
        Object o = m.get("parts");
        if (!(o instanceof List<?> list)) return "";
        List<String> out = new ArrayList<>();
        for (Object x : list) {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) x;
            String t = str(p.get("type"));
            if (t.equals("HOSPITAL") || t.equals("PATIENT")) continue;
            String line = str(p.get("label")) + " [" + str(p.get("investigatorName")) + "]: " + response(p);
            String why = reason(p);
            if (!why.isEmpty()) line += " - " + why;
            out.add(line);
        }
        return String.join(sep, out);
    }

    /** Pending part-transfer requests, e.g. "Patient part: requested". */
    private static String transfers(Map<String, Object> m) {
        Object o = m.get("parts");
        if (!(o instanceof List<?> list)) return "";
        List<String> out = new ArrayList<>();
        for (Object x : list) {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) x;
            Object st = p.get("transferStatus");
            if (st != null) out.add(str(p.get("label")) + ": " + ("FORWARDED".equals(st) ? "forwarded to Main Admin" : "requested"));
        }
        return String.join("; ", out);
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static String cell(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return "";
        if (v instanceof Boolean b) return b ? "Yes" : "";
        String s = v.toString();
        if (key.equals("createdAt") && s.length() >= 10) return s.substring(0, 10);
        return s;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Stops spreadsheet formula injection: a cell starting with = + - @ is prefixed so Excel treats it as text. */
    private static String safe(String s) {
        if (s == null || s.isEmpty()) return "";
        char c = s.charAt(0);
        return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + s : s;
    }
}

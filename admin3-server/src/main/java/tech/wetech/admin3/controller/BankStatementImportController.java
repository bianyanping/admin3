package tech.wetech.admin3.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tech.wetech.admin3.ocrApi.service.StatementImportService;

import java.io.IOException;

/**
 * 银行流水文件上传导入接口。
 *
 * <p>支持 PDF 与 Excel(.xlsx)：
 * PDF 自动判断原生文本层还是扫描件（扫描件先走百度 OCR）；
 * Excel 自动识别表头行与列名，最终统一走同一套校验与入库逻辑。
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/bank-statements")
public class BankStatementImportController {

  private final StatementImportService statementImportService;

  public BankStatementImportController(StatementImportService statementImportService) {
    this.statementImportService = statementImportService;
  }

  /**
   * 上传流水文件（PDF / Excel）并解析入库。
   *
   * @param file     银行流水 PDF 或 Excel(.xlsx)
   * @param bankCode 可选，银行编码（ICBC/PSBC/GXRCB/ABCHX/CCB/GLGMCZYH/GLYH/WECHAT），不传则自动识别
   * @param forceOcr 可选，true 时强制走 OCR（仅 PDF 有效，默认 false 自动判断）
   */
  @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<StatementImportService.ImportResult> importStatement(
    @RequestParam("file") MultipartFile file,
    @RequestParam(value = "bankCode", required = false) String bankCode,
    @RequestParam(value = "forceOcr", defaultValue = "false") boolean forceOcr) throws IOException {
    return ResponseEntity.ok(statementImportService.importStatement(file, bankCode, forceOcr));
  }
}

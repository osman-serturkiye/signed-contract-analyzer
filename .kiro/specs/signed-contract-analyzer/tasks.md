# Implementation Plan: signed-contract-analyzer

## Overview

Java 21 kütüphanesi + Python FastAPI mikroservisi ikilisini aşamalı olarak inşa eden bu plan; önce proje iskeletini ve veri modellerini kurar, ardından Java ve Python bileşenlerini katman katman uygular, son olarak entegrasyon testleri ve rapor üretimini ekler. Her görev bir öncekinin üzerine inşa edilir; hiçbir kod yalnasın (orphaned) kalmaz.

---

## Tasks

- [ ] 1. Proje İskeleti ve Build Sistemi Kurulumu
  - Java 21 Gradle `build.gradle` dosyasını oluştur: `sourceCompatibility`, `targetCompatibility`, tüm bağımlılıklar (pdfbox, poi-ooxml, java-diff-utils, flexmark-all, openhtmltopdf-pdfbox, jqwik, junit-jupiter, mockito-core)
  - `settings.gradle` ile proje adını `signed-contract-analyzer` olarak ayarla
  - `src/main/java/com/signedcontract/` ve `src/test/java/com/signedcontract/` dizin yapısını oluştur (config/, pipeline/, processor/, ocr/, clause/, diff/, report/, client/, ratelimit/, warning/, model/)
  - Python `python-service/requirements.txt` dosyasını tasarımdaki bağımlılıklarla güncelle
  - Python `python-service/config.py` ve `python-service/auth.py` dosyalarını `.env` okuma ve API key doğrulama ile oluştur
  - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.6_

- [x] 2. Java Veri Modelleri ve Temel Türler
  - [x] 2.1 Java record'larını ve enum'larını oluştur
    - `model/` altında şu record'ları yaz: `PageImage`, `OcrBlock`, `PageOcrResult`, `BoundingBox` (`withMargin` metodu dahil), `LanguageAnalysis`, `ColumnInfo`, `ColumnBBox`, `ClauseMapping`, `DiffResult`, `AiCompareResult`, `Signer`, `SignedContract`, `OriginalContract`, `PipelineResult`
    - `AnalysisWarning` record'unu ve `Severity` enum'unu oluştur
    - `ContractAnalysisException` (checked exception) sınıfını oluştur
    - `PipelineConfig` record'unu tüm alanlarıyla oluştur
    - _Requirements: 13.1, 16.1_

  - [x] 2.2 Property testi: BoundingBox.withMargin sınır içi kalma invariantı
    - **Property 3: BoundingBox Margin Sınır İçi Kalma Invariantı**
    - **Validates: Requirements 2.7**
    - jqwik ile rastgele (W, H, x, y, w, h, margin) değerleri üretip `withMargin` sonucunun her zaman `x_out ≥ 0`, `y_out ≥ 0`, `x_out + w_out ≤ W`, `y_out + h_out ≤ H` koşullarını sağladığını doğrula

- [x] 3. ConfigValidator
  - [x] 3.1 ConfigValidator sınıfını uygula
    - `validate(JSONObject)`, `getRequired()`, `getOptional()` metotlarını yaz
    - OCR adaptör değeri doğrulaması: `"PaddleOCR"`, `"Surya"`, `"AzureCognitiveService"`, `"MicrosoftFoundry"`, `"MistralOcr"`
    - AI adaptör değeri doğrulaması: `"OpenAI"`, `"AzureFoundryAI"`, `"ClaudeAI"`, `"OpenRouterAI"`
    - `BBOX_MARGIN` negatiflik kontrolü
    - `PYTHON_SERVICE_URL` URL format kontrolü
    - Seçilen adaptörün zorunlu `OCR_CONFIG` / `AI_CONFIG` alt alanlarını kontrol et
    - _Requirements: 25.1, 25.2, 25.3, 25.4, 25.5, 15.1, 15.2_

  - [x] 3.2 Property testi: Geçersiz OCR adaptörü reddi
    - **Property 11: Config Doğrulama — Geçersiz OCR Adaptörü Reddi**
    - **Validates: Requirements 25.2, 25.3**
    - jqwik ile geçerli set dışındaki rastgele string değerleri üretip `ConfigValidator.validate()` çağrısının her zaman `IllegalArgumentException` fırlattığını doğrula

  - [x] 3.3 ConfigValidator birim testlerini yaz
    - Her geçersiz config senaryosunu (negatif margin, hatalı adaptör adı, eksik endpoint) test et
    - _Requirements: 25.1–25.5_

- [ ] 4. PDFProcessor
  - [x] 4.1 PDFProcessor sınıfını uygula
    - `validate(String pdfPath)`: magic byte kontrolü, şifre tespiti, `MAX_FILE_SIZE_MB` sınırı, 1–500 sayfa kontrolü
    - `convertToPageImages(String pdfPath)`: PDFBox ile 300 DPI `BufferedImage` listesi üretimi
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 22.1, 22.2, 22.4_

  - [ ] 4.2 Property testi: PDF format reddi
    - **Property 1: PDF Format Reddi**
    - **Validates: Requirements 1.1, 1.3**
    - jqwik ile rastgele non-PDF dosya uzantıları/magic byte'ları üretip `validate()` çağrısının her zaman exception fırlattığını doğrula

  - [x] 4.3 Property testi: 300 DPI garantisi
    - **Property 2: Sayfa Görüntüsü 300 DPI Garantisi**
    - **Validates: Requirements 1.5, 1.6**
    - jqwik ile farklı sayfa sayıları (1–500 arası) için çıktı piksel boyutlarının 300 DPI ile uyumlu olduğunu doğrula

  - [x] 4.4 Dosya boyutu sınırı property testi
    - **Property 10: Dosya Boyutu Sınırı Zorunluluğu**
    - **Validates: Requirements 22.1, 22.2**
    - `MAX_FILE_SIZE_MB` aşan girdilerde `IllegalArgumentException` fırlatıldığını property olarak doğrula

  - [x] 4.5 PDFProcessorTest birim testlerini yaz
    - Format kontrolü, şifre tespiti, geçerli PDF testi
    - _Requirements: 1.1–1.6_

- [ ] 5. DocxProcessor
  - [x] 5.1 DocxProcessor sınıfını uygula
    - `parse(String docxPath)`: XXE korumalı SAX ayrıştırma, `.docx` format + bozukluk kontrolü, `MAX_FILE_SIZE_MB` sınırı
    - Regex + heading stili ile clause sınırı tespiti: `^\d+(\.\d+)*\.?\s` ve `MADDE/Madde` prefix desenleri
    - İç içe numaralandırma hiyerarşisi (1 → 1.1 → 1.1.1) koruması
    - Her clause'u Markdown formatında üret (tablo, paragraf, liste dönüşümü)
    - Başlık çıkarımı ve `additional` bölümü tespiti
    - Yinelenen madde numarası durumunda `-duplicate-N` sonek mantığı
    - `OriginalContract` JSON üretimi: `title`, `clauses`, `additional`
    - _Requirements: 8.1–8.4, 9.1–9.7, 22.3, 29.1, 29.2_

  - [ ] 5.2 Property testi: DOCX clause numarası regex tespiti
    - **Property 6: DOCX Clause Numarası Regex Tespiti**
    - **Validates: Requirements 9.2**
    - jqwik ile geçerli madde numaralandırma formatlarını (`"1."`, `"1.1"`, `"MADDE 1"`, `"14.3.2"`) üretip DocxProcessor'ın her birini yeni clause başlangıcı olarak tanıdığını doğrula

  - [ ] 5.3 Property testi: Yinelenen madde numarası benzersizlik garantisi
    - **Property 13: Yinelenen Madde Numarası Benzersizlik Garantisi**
    - **Validates: Requirements 29.2**
    - jqwik ile çakışan clause numaraları içeren girdiler üretip çıktı anahtarlarının (`"5"`, `"5-duplicate-1"`) her zaman benzersiz olduğunu doğrula

  - [ ] 5.4 DocxProcessorTest birim testlerini yaz
    - XXE güvenliği, clause ayrıştırma, Markdown çıktı, `additional` bölümü
    - _Requirements: 8.1–8.4, 9.1–9.7_

- [ ] 6. Checkpoint — Java temel bileşenleri
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Python FastAPI Mikroservis — Temel Altyapı
  - [x] 7.1 FastAPI uygulamasını ve /health endpoint'ini oluştur
    - `python-service/main.py`: FastAPI app, lifespan, CORS, global hata yönetimi, request body boyut sınırı (413 desteği)
    - `python-service/routers/health.py`: `/health` GET → `{"status": "ok"}` 200, auth gerektirmez
    - `python-service/auth.py`: `Authorization: Bearer <key>` header doğrulama dependency'si → 401 Unauthorized
    - `python-service/config.py`: `.env` okuma, zorunlu değer doğrulama
    - _Requirements: 4.9, 4.10, 27.1, 27.2, 27.3, 22.5_

  - [ ] 7.2 Health endpoint birim testini yaz
    - `/health` GET → 200 `{"status": "ok"}` yanıtını ve auth gerektirmediğini test et
    - _Requirements: 27.1–27.3_

- [ ] 8. Python OCR Adaptörleri ve /ocr/* Endpoint'leri
  - [x] 8.1 OcrAdapter abstract class ve PaddleOCR + Surya adaptörlerini oluştur
    - `adapters/ocr/base.py`: `OcrAdapter` abstract class (`perform_ocr`, `get_adapter_name`)
    - `adapters/ocr/paddleocr_adapter.py`: PP-StructureV3 pipeline; layout analizi, tablo, çok sütun okuma sırası, Markdown çıktı
    - `adapters/ocr/surya_adapter.py`: Surya OCR 2 VLM; layout + OCR + tablo
    - `routers/ocr.py`: `/ocr/paddleocr` ve `/ocr/surya` POST endpoint'leri; `OcrRequest` → `OcrResponse` (`blocks`, `page_width`, `page_height`)
    - Pydantic modelleri: `OcrRequest`, `OcrBlock`, `BoundingBoxModel`, `OcrResponse`
    - _Requirements: 4.1–4.8, 4.11, 4.12, 4.13_

  - [x] 8.2 Cloud OCR adaptörlerini oluştur
    - `adapters/ocr/azure_adapter.py`: Azure Cognitive Services
    - `adapters/ocr/foundry_adapter.py`: Azure AI Foundry
    - `adapters/ocr/mistral_adapter.py`: Mistral OCR
    - `routers/ocr.py`'ye `/ocr/azure`, `/ocr/foundry`, `/ocr/mistral` endpoint'lerini ekle
    - _Requirements: 4.2, 4.4, 15.1, 15.2_

  - [ ] 8.3 OCR endpoint birim testlerini yaz
    - Mock OCR motoru ile `OcrResponse` yapısını, 401 davranışını, 503 fallback'i test et
    - _Requirements: 4.9–4.14_

- [ ] 9. Python Image Servisi ve /image/* Endpoint'leri
  - [x] 9.1 ImageService ve /image/crop + /image/stitch endpoint'lerini oluştur
    - `services/image_service.py`: Pillow ile crop (margin clamp, max_dimension_px yeniden ölçekleme, compression_quality JPEG sıkıştırma) ve stitch (dikey birleştirme, gap_px boşluk)
    - `routers/image.py`: `/image/crop` POST → `CropResponse`; `/image/stitch` POST → `StitchResponse`
    - Pydantic modelleri: `CropRequest`, `CropResponse`, `StitchRequest`, `StitchResponse`
    - Geçici dosyaların istek sonunda silinmesi (Req 21.5)
    - _Requirements: 2.9, 2.10, 3.5, 3.6, 26.1, 26.2, 21.5_

  - [x] 9.2 Image endpoint birim testlerini yaz
    - Margin clamp, ölçekleme, JPEG sıkıştırma, dikey stitch davranışlarını test et
    - _Requirements: 2.10, 3.6, 26.1, 26.2_

- [x] 10. Python AI Adaptörleri ve /ai/compare Endpoint'i
  - [x] 10.1 AiAdapter abstract class ve tüm AI adaptörlerini oluştur
    - `adapters/ai/base.py`: `AiAdapter` abstract class (`compare`, `get_adapter_name`)
    - `adapters/ai/openai_adapter.py`, `azure_foundry_adapter.py`, `claude_adapter.py`, `openrouter_adapter.py`
    - `routers/ai.py`: `/ai/compare` POST → `AiCompareResponse` (`changes`, `result`)
    - Pydantic modelleri: `AiCompareRequest`, `AiCompareResponse`
    - Erişim hatası → `changes: null` + hata mesajı
    - _Requirements: 11.1–11.9_

  - [ ] 10.2 AI endpoint birim testlerini yaz
    - Başarılı karşılaştırma, servis erişim hatası (`changes: null`), 401 davranışlarını test et
    - _Requirements: 11.7, 11.8_

- [ ] 11. Java HTTP Client'ları (Java→Python Köprüsü)
  - [ ] 11.1 OcrClient, ImageClient, AiClient, ReportClient sınıflarını uygula
    - Java 11+ `HttpClient` ile her Python endpoint'ine karşılık gelen metodları yaz
    - `OcrClient.performOcr()`: `/ocr/{adapter}` POST
    - `ImageClient.crop()` ve `ImageClient.stitch()`: `/image/crop`, `/image/stitch` POST
    - `AiClient.compare()`: `/ai/compare` POST
    - `ReportClient.generateHtml()` ve `generatePdf()`: `/report/html`, `/report/pdf` POST
    - Her client'ta `Authorization: Bearer <key>` header ekleme
    - 401 → anında `ContractAnalysisException`; 429/5xx → exp. backoff (max 3 retry: 1s, 2s, 4s)
    - _Requirements: 4.10, 4.14, 11.8, 22.5_

  - [ ] 11.2 HTTP client birim testlerini yaz (Mockito ile mock sunucu)
    - Retry mantığı, 401 yayılımı, timeout davranışını test et
    - _Requirements: 23.3, 23.4_

- [ ] 12. RateLimiter
  - [ ] 12.1 RateLimiter sınıfını uygula
    - `Semaphore` tabanlı `submit(Callable<T>)` metodu
    - `shutdown()` metodu
    - `AI_MAX_CONCURRENCY` (varsayılan 5) ve `OCR_MAX_CONCURRENCY` (varsayılan 3) desteği
    - _Requirements: 23.1, 23.2_

  - [ ] 12.2 Property testi: RateLimiter eşzamanlılık üst sınırı
    - **Property 12: RateLimiter Eşzamanlılık Üst Sınırı**
    - **Validates: Requirements 23.1, 23.2**
    - jqwik ile farklı N değerleri için eş zamanlı çalışan görev sayısının hiçbir zaman N'i aşmadığını doğrula

- [ ] 13. LanguageDetector
  - [ ] 13.1 LanguageDetector sınıfını uygula
    - `detect(List<PageImage>)`: İlk sayfada `OcrClient` ile OCR çağrısı
    - X koordinatı boşluk analizi ile dikey sütun tespiti
    - `PRIMARY_LANGUAGE` config override desteği
    - Belirsizlik → tek dilli varsayım + WARN
    - `LanguageAnalysis` döndür: `multilingual`, `activeLanguage`, `columns`
    - _Requirements: 20.1–20.11_

  - [ ] 13.2 LanguageDetector birim testlerini yaz (OcrClient mock'lanmış)
    - Tek dilli, çok dilli kolon tespiti, PRIMARY_LANGUAGE override senaryolarını test et
    - _Requirements: 20.3, 20.4, 20.5, 20.9_

- [ ] 14. ClauseCoordinateMatcher
  - [ ] 14.1 ClauseCoordinateMatcher sınıfını uygula
    - `map(ocrResults, detectedClauses, languageAnalysis)`: Her OCR bloğunun koordinat merkezini clause bbox'larıyla kesişim kontrolü
    - Çok dilli düzende yalnızca `active_language` sütun bbox'ı içindeki blokları dahil et
    - Boş clause → WARN + `content: ""`
    - Yinelenen madde numarası → `-duplicate-N` sonek
    - Tablo/paragraf/metin bloklarını ayrı elementler olarak koru
    - _Requirements: 5.1–5.6, 20.7, 29.2_

  - [ ] 14.2 Property testi: ClauseCoordinateMatcher tekil atama invariantı
    - **Property 4: ClauseCoordinateMatcher Tekil Atama Invariantı**
    - **Validates: Requirements 5.1**
    - jqwik ile rastgele OCR blok ve clause bbox listesi üretip hiçbir bloğun birden fazla clause'a atanmadığını doğrula

  - [ ] 14.3 ClauseCoordinateMatcherTest birim testlerini yaz
    - Bbox kesişim algoritması, sınır koordinatları, boş clause senaryolarını test et
    - _Requirements: 5.1–5.6_

- [ ] 15. Checkpoint — Python servisi + Java istemcileri + Coordinate Matching
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 16. SignatureDetector
  - [ ] 16.1 SignatureDetector sınıfını uygula
    - `detect(pageImages, ocrResults)`: OCR bloklarında `type: "signature"` olanları tespit et
    - Her imza bloğu için `ImageClient.crop()` ile görüntü kırp ve Base64 kodla
    - Yakın OCR bloklarından imzacı adını oku; tespit edilemezse `name: ""` + WARN
    - `List<Signer>` döndür
    - _Requirements: 6.1–6.5_

  - [ ] 16.2 SignatureDetector birim testlerini yaz (ImageClient mock'lanmış)
    - İmza tespiti, boş ad fallback, erişilemeyen görüntü senaryolarını test et
    - _Requirements: 6.1–6.5_

- [ ] 17. DiffEngine
  - [ ] 17.1 DiffEngine sınıfını uygula
    - `diff(signedMarkdown, originalMarkdown)`: java-diff-utils ile metin karşılaştırma → `DiffResult(changes, result)`
    - `diffAll(signedClauses, originalClauses, maxConcurrency)`: Tüm clause'lar için toplu diff
    - Aynı içerik → `changes: false`, `result: ""`
    - Farklı içerik → `changes: true`, `result: Markdown fark açıklaması`
    - Yalnızca imzalı/yalnızca orijinal clause işaretlemesi
    - _Requirements: 10.1–10.6_

  - [ ] 17.2 Property testi: DiffEngine değişimsizlik invariantı
    - **Property 7: DiffEngine Değişimsizlik Invariantı**
    - **Validates: Requirements 10.3**
    - jqwik ile rastgele string'ler için `diff(s, s)` çağrısının her zaman `changes: false, result: ""` döndürdüğünü doğrula

  - [ ] 17.3 Property testi: DiffEngine değişim tespiti
    - **Property 8: DiffEngine Değişim Tespiti**
    - **Validates: Requirements 10.4**
    - jqwik ile `s1 ≠ s2` çiftleri için `diff(s1, s2)` sonucunun her zaman `changes: true` olduğunu doğrula

  - [ ] 17.4 DiffEngineTest birim testlerini yaz
    - Özdeş içerik, tek taraflı eksik clause, kısmi değişim senaryolarını test et
    - _Requirements: 10.1–10.6_

- [ ] 18. ReportAssembler
  - [ ] 18.1 ReportAssembler sınıfını uygula
    - `assemble(signedContract, originalContract, diffs, aiResults, languageAnalysis, signers)` → `PipelineResult`
    - `getSignedContractJson()`: `title`, `clauses` (content + image), `signers`, `additional`, `language_analysis`
    - `getOriginalContractJson()`: `title`, `clauses` (content), `additional`
    - `getAnalysisJson()`: `analyze_clauses` (signed_image, signed_content, original_content, diff, diff_ai)
    - `getResultJson()`: üç çıktıyı birleştir
    - Tüm clause anahtarlarını doğal madde sıralamasına (1 < 1.1 < 1.2 < 2 < 10) göre düzenle
    - `signed_image: null` durumunu handle et
    - JSON round-trip geçerliliği doğrulama
    - _Requirements: 7.1–7.5, 9.7, 12.1–12.6, 16.9–16.12_

  - [ ] 18.2 Property testi: Signed Contract JSON Round-Trip
    - **Property 5: Signed Contract JSON Round-Trip**
    - **Validates: Requirements 7.5, 12.6**
    - jqwik ile rastgele `SignedContract` nesneleri üretip serialize → deserialize sonucunun başlangıç ile eşdeğer olduğunu doğrula

  - [ ] 18.3 Property testi: Doğal madde sıralaması invariantı
    - **Property 9: Analiz Raporu Doğal Madde Sıralaması**
    - **Validates: Requirements 12.1, 12.4**
    - jqwik ile rastgele sıralı clause anahtar listeleri için `analyze_clauses` anahtarlarının her zaman doğal sırada olduğunu doğrula

  - [ ] 18.4 ReportAssembler birim testlerini yaz
    - Doğal sıralama, `null` signed_image, JSON geçerliliği, `additional` işleme senaryolarını test et
    - _Requirements: 7.1–7.5, 12.1–12.6_

- [ ] 19. AnalysisPipeline ve WarningCollector
  - [ ] 19.1 WarningCollector sınıfını uygula
    - `addWarning(component, severity, message)`, `getWarnings()`, `clear()` metotlarını yaz
    - Thread-safe ekleme (CopyOnWriteArrayList)
    - _Requirements: 13.1–13.4, 24.1–24.4_

  - [ ] 19.2 AnalysisPipeline sınıfını uygula
    - 12 adımlı pipeline akışını `execute(pdfPath, docxPath)` içinde orkestre et
    - Adım 10: `CompletableFuture.allOf()` ile `DiffEngine.diffAll()` ve `AiClient` çağrılarını paralel yürüt; `AI_MAX_CONCURRENCY` ve `OCR_MAX_CONCURRENCY` için `RateLimiter` kullan
    - Her bileşen hatasını tabloya göre ele al: kritik → exception, kritik olmayan → `WarningCollector`
    - `RETENTION_SECONDS` sonunda geçici dosya temizliği
    - _Requirements: 14.1–14.3, 21.1–21.4, 23.3, 23.4_

  - [ ] 19.3 AnalysisPipeline birim testlerini yaz (tüm bağımlılıklar mock'lanmış)
    - Paralel yürütme sırası, hata yayılımı, warning birikimi senaryolarını test et
    - _Requirements: 14.1–14.3_

- [ ] 20. ContractAnalyzer (Public API)
  - [ ] 20.1 ContractAnalyzer sınıfını uygula
    - Parametresiz constructor
    - Fluent API: `config()`, `setSignedContractPdf()`, `setOriginalContractDoc()`, `start()` her biri `this` döndürür
    - `start()` durumu: `IDLE → RUNNING → COMPLETED/FAILED`; ikinci `start()` çağrısında `IllegalStateException`
    - `ConfigValidator` ile `config()` çağrısında doğrulama
    - `getResultJson()`, `getSignedContractJson()`, `getOriginalContractJson()`, `getAnalysisJson()`: `start()` öncesi `null` döner
    - `getWarnings()`: `start()` öncesi boş liste döner
    - `exportHtml(outputFilePath)`: `ReportClient.generateHtml()` → başarısız (bağlantı/5xx) ise flexmark-all ile fallback HTML; 401 → doğrudan exception
    - `exportPdf(outputFilePath)`: `ReportClient.generatePdf()` → başarısız ise flexmark + openhtmltopdf fallback; her ikisi de başarısız → `ContractAnalysisException`
    - `start()` öncesi `exportHtml`/`exportPdf` → `IllegalStateException`
    - `MAX_RESULT_SIZE_MB` aşımında WARN ekle
    - _Requirements: 16.1–16.14, 17.1, 17.8, 17.9, 18.1, 18.9, 30.1–30.6, 26.3_

  - [ ] 20.2 ContractAnalyzerTest birim testlerini yaz
    - Fluent API method chaining, ikinci `start()` çağrısı, `getWarnings()`, fallback akışlarını test et
    - _Requirements: 16.1–16.14_

- [ ] 21. Checkpoint — Pipeline ve Public API
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 22. Python Rapor Servisi ve /report/* Endpoint'leri
  - [ ] 22.1 ReportService ve /report/html + /report/pdf endpoint'lerini oluştur
    - `services/report_service.py`: Jinja2 ile `report-template.html` render → HTML; WeasyPrint ile HTML → PDF
    - `routers/report.py`: `/report/html` POST → `text/html; charset=UTF-8`; `/report/pdf` POST → `application/pdf`
    - Pydantic model: `ReportRequest` (`signed_contract`, `original_contract`, `analysis`)
    - `report-template.html`: Rapor Tablosu (Madde No, Clause Image, Signed Content, Original Content, Java Diff, AI Diff); Markdown → HTML render; diff değişikliklerinde kırmızı/yeşil arka plan; başlık + tarih/saat; doğal madde sıralaması; görüntüler `<img>` olarak gömülü
    - Tek şablon hem HTML hem PDF için kullanılır
    - Her endpoint'te ReportServiceApiKey doğrulaması (401 desteği)
    - _Requirements: 4.9, 17.2–17.7, 18.2–18.8_

  - [ ] 22.2 Report endpoint birim testlerini yaz
    - HTML yanıt content-type, PDF binary, 401 davranışı, doğal sıralama, görüntü gömme senaryolarını test et
    - _Requirements: 17.3, 17.4, 18.3, 18.4_

- [ ] 23. Loglama Altyapısı
  - [ ] 23.1 Java loglama altyapısını yapılandır
    - Java tarafı için `INFO/WARN/ERROR` seviyeli, zaman damgası (ISO-8601) + bileşen adı + mesaj içeren log formatı
    - `LOG_FILE_PATH` yapılandırılmışsa dosyaya + konsola; yapılandırılmamışsa yalnızca konsola yaz
    - `LOG_FORMAT: "json"` seçeneğinde her satır geçerli JSON nesnesi olarak yazılsın
    - _Requirements: 13.1–13.4, 28.1–28.4_

  - [ ] 23.2 Python loglama yapılandırmasını tamamla
    - `python-service/config.py`'ye `LOG_LEVEL` ve `LOG_FORMAT` desteği ekle; structured JSON logging
    - _Requirements: 28.5_

- [ ] 24. Entegrasyon Testi
  - [ ] 24.1 SampleContractIntegrationTest'i yaz
    - `samples/sample-01/signed.pdf` ve `samples/sample-01/original.docx` kullanarak tam pipeline testi
    - Python servisi yerine local mock HTTP sunucu ile çalıştır
    - `getResultJson()` çıktısının `signed_contract`, `original_contract`, `analysis` alanlarını içerdiğini doğrula
    - `analyze_clauses` anahtarlarının doğal sırada olduğunu doğrula
    - `getWarnings()` çağrısının liste döndürdüğünü doğrula
    - _Requirements: 19.5, 19.7_

  - [ ] 24.2 Entegrasyon testi: Fallback rapor akışı
    - Python `/report/html` erişilemez olduğunda flexmark fallback'in devreye girdiğini ve `getWarnings()` listesine ekleme yaptığını test et
    - _Requirements: 30.1–30.4_

- [ ] 25. Son Checkpoint — Tüm Testler
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- `*` işaretli görevler isteğe bağlıdır; MVP için atlanabilir
- Her görev spesifik requirement numaralarını referans almaktadır
- Property testleri jqwik (JUnit 5 uyumlu) kullanılarak yazılır; Python tarafında pytest + hypothesis kullanılabilir
- Checkpoint'ler, her aşamanın sağlıklı tamamlandığını doğrulamak için koyulmuştur
- Java tarafı hiçbir dış servise (OCR, AI, görüntü işleme) doğrudan bağlanmaz; tüm dış çağrılar Python üzerinden gider
- Fallback mekanizmaları (flexmark + openhtmltopdf) yalnızca Python servis erişilemez durumdayken devreye girer; 401 hatalarında fallback yoktur

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["2.1", "7.1"] },
    { "id": 1, "tasks": ["2.2", "3.1", "8.1", "9.1"] },
    { "id": 2, "tasks": ["3.2", "3.3", "4.1", "8.2", "9.2", "10.1"] },
    { "id": 3, "tasks": ["4.2", "4.3", "4.4", "4.5", "5.1", "8.3", "10.2", "11.1"] },
    { "id": 4, "tasks": ["5.2", "5.3", "5.4", "11.2", "12.1"] },
    { "id": 5, "tasks": ["12.2", "13.1"] },
    { "id": 6, "tasks": ["13.2", "14.1"] },
    { "id": 7, "tasks": ["14.2", "14.3", "16.1", "17.1"] },
    { "id": 8, "tasks": ["16.2", "17.2", "17.3", "17.4", "18.1"] },
    { "id": 9, "tasks": ["18.2", "18.3", "18.4", "19.1"] },
    { "id": 10, "tasks": ["19.2", "22.1"] },
    { "id": 11, "tasks": ["19.3", "20.1", "22.2"] },
    { "id": 12, "tasks": ["20.2", "23.1", "23.2"] },
    { "id": 13, "tasks": ["24.1"] },
    { "id": 14, "tasks": ["24.2"] }
  ]
}
```

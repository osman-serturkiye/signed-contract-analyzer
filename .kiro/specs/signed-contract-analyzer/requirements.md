# Requirements Document

## Introduction

Bu doküman, imzalı taranmış sözleşmelerin analiz edilmesine yönelik Java tabanlı bir çözümün gereksinimlerini tanımlamaktadır. Sistem; taranmış PDF formatındaki imzalı sözleşmeyi alır, maddelere ayırır, sayfa düzeyinde OCR ile metin çıkarır, imzaları tespit eder ve ardından orijinal Word (.docx) dosyasıyla karşılaştırmalı analiz yaparak kapsamlı bir rapor üretir.

## Glossary

- **Sistem**: İmzalı sözleşme analiz uygulamasının tamamı
- **PDF_İşleyici**: Taranmış PDF dosyasını alıp sayfa görüntülerine dönüştüren bileşen
- **Madde_Tespit_Motoru**: PDF sayfalarındaki sözleşme maddelerini ve konumlarını (bounding box) tespit eden bileşen
- **OCR_Motoru**: Sayfa görüntüleri üzerinde optik karakter tanıma işlemi yapan ve her tanınan bloğun koordinat bilgisini (word/line düzeyinde bbox) döndüren bileşen
- **OcrAdapter**: Farklı OCR motorlarının aynı arayüz üzerinden kullanılmasını sağlayan adaptör arayüzü; Azure Cognitive Service (Microsoft), Microsoft Foundry, Mistral OCR (cloud) ve PaddleOCR, Surya (self-hosted) tarafından implement edilir
- **PageLevelOcr**: OCR işleminin clause-image'lere ayrı ayrı değil, tüm sayfaya bir kez uygulandığı yaklaşım
- **BboxMargin**: Clause görüntüsü kırpılırken BoundingBox'a her yönde eklenen yapılandırılabilir kenar boşluğu (piksel cinsinden); varsayılan değeri her yön için 5px'tir
- **ClauseCoordinateMapping**: Her clause'un metin içeriğinin, OCR çıktısındaki koordinat bilgileriyle BoundingBox tespiti sonuçları çakıştırılarak oluşturulduğu eşleştirme süreci
- **Markdown_Dönüştürücü**: OCR çıktısını Markdown formatına çeviren bileşen
- **İmza_Tespit_Motoru**: Sözleşmedeki imza alanlarını tespit eden ve imzacı bilgilerini çıkaran bileşen
- **DOCX_İşleyici**: Word (.docx) dosyasını Apache POI kullanarak okuyup içeriğini yapısal veriye dönüştüren bileşen
- **Diff_Motoru**: İmzalı sözleşme ile orijinal Word içeriğini java-diff-utils kullanarak karşılaştıran bileşen
- **AI_Karşılaştırıcı**: Madde içeriklerini yapay zeka kullanarak semantik düzeyde karşılaştıran bileşen
- **Rapor_Üretici**: Analiz sonuçlarını JSON formatında birleştiren ve raporlayan bileşen
- **Clause (Madde)**: Bir sözleşmenin numaralandırılmış bölümü veya alt bölümü (örn: "1", "1.1", "14.3")
- **BoundingBox (bbox)**: Bir sayfadaki maddenin piksel koordinatları ile tanımlanan dikdörtgen alan
- **Clause-Image**: Bir maddenin bbox bilgisine ve BboxMargin değerine göre kırpılmış görüntüsü; birden fazla sayfa içerebilir
- **İmzacı**: Sözleşmeyi imzalayan kişi; adı ve imza görüntüsü ile temsil edilir
- **Ek Protokol**: Sözleşmenin ek bölümleri veya protokolleri
- **Round-Trip**: Bir verinin dönüştürülüp geri dönüştürüldüğünde başlangıç değerini koruması özelliği
- **PythonOcrMicroservice**: PaddleOCR (PP-StructureV3) ve Surya OCR motorlarını, HTML raporu ve PDF raporu üretimini tek bir Python FastAPI servisi olarak sunan; Java tarafının HTTP üzerinden çağırdığı yerel mikro servis
- **PP-StructureV3**: PaddleOCR v3.x'in layout analizi pipeline'ı; metin/başlık/paragraf/görüntü/tablo bloklarını tespit eder, koordinat ve Markdown çıktısı üretir
- **OcrServiceApiKey**: Python OCR microservisine yetkisiz erişimi engelleyen, HTTP Authorization header ile iletilen API anahtarı
- **ReportMicroservice**: PythonOcrMicroservice bünyesinde çalışan rapor üretim bileşeni; `analyze.json` alıp HTML veya PDF dosyası döndüren `/report/html` ve `/report/pdf` endpoint'lerini sunar
- **ReportServiceApiKey**: ReportMicroservice endpoint'lerine yetkisiz erişimi engelleyen, HTTP Authorization header ile iletilen API anahtarı (OcrServiceApiKey ile aynı servis anahtarı kullanılabilir)
- **AiAdapter**: Farklı AI servislerinin aynı arayüz üzerinden kullanılmasını sağlayan adaptör arayüzü; OpenAI, Azure Foundry AI, Claude (Anthropic) ve OpenRouter tarafından implement edilir
- **AiAdapterApiKey**: AI servisine kimlik doğrulama için kullanılan, yapılandırma dosyasından okunan API anahtarı
- **ContractAnalyzer**: Tüm analiz sürecini kapsülleyen ana Java sınıfı; public API giriş noktası
- **ContractAnalysisException**: ContractAnalyzer pipeline'ında kritik hata oluştuğunda fırlatılan checked exception
- **Fluent API**: Her method'un `this` döndürerek zincirleme çağrıya olanak tanıdığı API tasarım deseni
- **HTML_Rapor_Üretici**: Tek bir HTML şablonu (report-template.html) kullanarak analiz sonuçlarını tablo formatında render eden bileşen; PDF çıktısı da bu şablondan üretilen HTML üzerinden WeasyPrint/Playwright ile dönüştürülür
- **PDF_Rapor_Üretici**: HTML_Rapor_Üretici tarafından üretilen HTML çıktısını PDF'e dönüştüren bileşen; WeasyPrint veya Playwright kullanır — ayrı bir şablon yoktur
- **ReportTemplate**: Python microservis bünyesinde bulunan tek HTML şablon dosyası (`report-template.html`); hem HTML hem PDF çıktısı bu şablon üzerinden üretilir
- **Rapor Tablosu**: Her satırın bir clause'u temsil ettiği; Madde No, Clause Image, Signed Content, Original Content, Java Diff ve AI Diff kolonlarından oluşan karşılaştırma tablosu
- **DilTespitMotoru**: PDF yüklendikten hemen sonra çalışan, belgenin dil yapısını (tek dilli / çok dilli) ve sayfa düzenini (tek sütun / çok sütun) otomatik tespit eden bileşen
- **KolonDüzeni**: Sayfanın dikey sütunlara bölündüğü çok dilli sözleşme düzeni; her sütun farklı bir dili temsil eder
- **DikilKolonBBox**: Çok sütunlu sayfada her sütunun sayfa koordinatlarına göre tanımlanan dikey sınır bölgesi
- **AktifDil**: Karşılaştırma ve analiz için esas alınan dil; tek dilli sözleşmelerde tek dil, çok dilli sözleşmelerde kullanıcının birincil dili

---

## Requirements

---

### Requirement 1: PDF Yükleme ve Doğrulama

**User Story:** Bir analist olarak, taranmış imzalı sözleşmeyi PDF formatında sisteme yüklemek istiyorum; böylece analiz sürecini başlatabileyim.

#### Acceptance Criteria

1. THE Sistem SHALL taranmış sözleşme dosyalarını yalnızca PDF formatında kabul etmelidir.
2. WHEN bir PDF dosyası yüklendiğinde, THE PDF_İşleyici SHALL dosyanın geçerli bir PDF olup olmadığını doğrulamalıdır.
3. IF yüklenen dosya PDF formatında değilse, THEN THE Sistem SHALL kullanıcıya "Geçersiz dosya formatı: yalnızca PDF kabul edilmektedir" hata mesajını döndürmelidir.
4. IF yüklenen PDF dosyası bozuksa veya okunamıyorsa, THEN THE PDF_İşleyici SHALL kullanıcıya dosya bozukluğunu belirten bir hata mesajı döndürmelidir.
5. WHEN geçerli bir PDF dosyası yüklendiğinde, THE PDF_İşleyici SHALL her sayfayı en az 300 DPI çözünürlükte görüntüye dönüştürmelidir.
6. THE PDF_İşleyici SHALL çok sayfalı PDF dosyalarını (1 ile 500 sayfa arasında) desteklemelidir.

---

### Requirement 2: Madde Tespiti ve BoundingBox Çıkarımı

**User Story:** Bir analist olarak, sözleşmedeki her maddenin otomatik olarak tespit edilmesini istiyorum; böylece her madde ayrı bir birim olarak işlenebilsin.

#### Acceptance Criteria

1. WHEN PDF görüntüleri hazırlandığında, THE Madde_Tespit_Motoru SHALL her maddeyi ve alt maddeyi (örn: "1", "1.1", "14.3") sayfalar üzerinde tespit etmelidir.
2. THE Madde_Tespit_Motoru SHALL her tespit edilen madde için sayfa numarası ve piksel koordinatlarını (x, y, genişlik, yükseklik) kapsayan BoundingBox bilgisini üretmelidir.
3. WHEN bir madde birden fazla sayfaya yayıldığında, THE Madde_Tespit_Motoru SHALL ilgili sayfaların tüm BoundingBox bilgilerini o maddeyle ilişkilendirmelidir.
4. THE Madde_Tespit_Motoru SHALL iç içe geçmiş madde numaralandırmayı (örn: "14", "14.1", "14.1.2") hiyerarşik olarak tanımlayabilmelidir.
5. IF bir madde tespit edilemiyorsa, THEN THE Madde_Tespit_Motoru SHALL bu durumu günlük kaydına (log) yazmalı ve işlemi diğer maddeler için sürdürmelidir.
6. WHEN BoundingBox tespiti tamamlandığında, THE Madde_Tespit_Motoru SHALL her madde için ilgili sayfadan clause-image üretimi amacıyla BoundingBox koordinatlarına BboxMargin değerini ekleyerek kırpma işlemi gerçekleştirmelidir.
7. WHEN BboxMargin uygulandığında, THE Madde_Tespit_Motoru SHALL hesaplanan kırpma alanının sayfa sınırlarını aşmamasını sağlamalı; aşma durumunda ilgili yöndeki koordinatı sayfa sınırına sabitlenmelidir.
8. THE Madde_Tespit_Motoru SHALL BboxMargin değerini yapılandırma dosyasından okumalı; yapılandırma dosyasında belirtilmemişse her yön için 5px varsayılan değerini kullanmalıdır.

---

### Requirement 3: Çok Sayfalı Madde Görüntüsü Birleştirme

**User Story:** Bir analist olarak, birden fazla sayfaya yayılan maddelerin tek bir bütünleşik görüntü olarak temsil edilmesini istiyorum; böylece madde içeriği eksiksiz analiz edilebilsin.

#### Acceptance Criteria

1. WHEN bir madde birden fazla sayfaya yayıldığında, THE Madde_Tespit_Motoru SHALL her sayfadan ilgili BoundingBox bölgelerini BboxMargin değeri uygulanmış koordinatlarla ayrı ayrı kırpmalıdır.
2. WHEN çok sayfalı madde kırpma işlemi tamamlandığında, THE Madde_Tespit_Motoru SHALL kırpılan görüntüleri sayfa sırasına göre dikey olarak birleştirerek tek bir clause-image üretmelidir.
3. THE Madde_Tespit_Motoru SHALL birleştirilmiş clause-image görüntüsünü Base64 formatında kodlamalıdır.
4. IF bir sayfanın görüntüsü kırpma işlemi sırasında erişilemez durumdaysa, THEN THE Madde_Tespit_Motoru SHALL bu durumu hata olarak kaydetmeli ve ilgili maddeyi eksik işaretlemelidir.

---

### Requirement 4: OCR Adaptör Mimarisi ve Sayfa Düzeyinde OCR İşlemi

**User Story:** Bir analist olarak, farklı OCR motorlarının tek bir arayüz üzerinden kullanılabilmesini ve OCR işleminin tüm sayfaya bir kez uygulanmasını istiyorum; böylece madde içerikleri doğru koordinat eşleşmesiyle elde edilebilsin.

#### Acceptance Criteria

1. THE Sistem SHALL OCR işlemini OcrAdapter arayüzü üzerinden yürütmelidir; kullanılacak adaptör süreç başında yapılandırma dosyasından seçilmelidir.
2. THE OcrAdapter arayüzü; Azure Cognitive Service, Microsoft Foundry, Mistral OCR (cloud) ve PaddleOCR, Surya (self-hosted) tarafından implement edilmelidir.
3. THE Sistem SHALL seçilen OcrAdapter'ı dışarıdan kullanan tüm bileşenler açısından hangi adaptörün kullanıldığından bağımsız, aynı arayüzde çalışacak şekilde tasarlanmalıdır.
4. THE Sistem SHALL her OcrAdapter'ın endpoint, api-key ve dil ayarı gibi yapılandırma parametrelerini yapılandırma dosyasından okumalıdır.
5. WHEN bir PDF sayfası işlendiğinde, THE OCR_Motoru SHALL PageLevelOcr yaklaşımıyla OCR işlemini tüm sayfaya bir kez uygulamalıdır; her clause-image'e ayrı ayrı OCR uygulanmamalıdır.
6. THE OCR_Motoru SHALL OCR işlemi sonucunda her tanınan bloğun metin içeriğini ve sayfa üzerindeki koordinat bilgisini (word veya line düzeyinde bbox) birlikte döndürmelidir.
7. THE OCR_Motoru SHALL Türkçe ve İngilizce karakterleri (özel karakterler dahil: ğ, ü, ş, ı, ö, ç) doğru biçimde tanıyabilmelidir.
8. IF bir sayfa üzerinde OCR işlemi başarısız olursa, THEN THE OCR_Motoru SHALL ilgili sayfaya ait tüm maddelerin içerik alanını boş bırakmalı ve hatayı günlüğe kaydetmelidir.
9. THE PythonOcrMicroservice SHALL hem PaddleOCR (PP-StructureV3) hem de Surya OCR motorlarını tek bir FastAPI servisi üzerinden sunmalıdır; `/ocr/paddleocr`, `/ocr/surya`, `/report/html` ve `/report/pdf` endpoint'leri erişilebilir olmalıdır.
10. THE PythonOcrMicroservice SHALL her istekte HTTP Authorization header üzerinden OcrServiceApiKey doğrulaması yapmalıdır; geçersiz veya eksik API anahtarıyla gelen istekler 401 Unauthorized yanıtı ile reddedilmelidir.
11. THE PythonOcrMicroservice SHALL her OCR isteğine karşılık olarak şu yapıda JSON yanıt döndürmelidir:
    - `blocks`: Her bloğun şu alanları içerdiği dizi:
      - `type`: Blok türü (text, title, paragraph, table, image, signature)
      - `bbox`: x, y, width, height koordinatları (piksel cinsinden)
      - `content`: Markdown formatında metin içeriği (tablolar Markdown tablo olarak)
      - `confidence`: Güven skoru (0.0 - 1.0 arası float; desteklemeyen motorlar için null)
12. THE PaddleOCR adaptörü SHALL PaddleOCR v3.x PP-StructureV3 pipeline'ını kullanmalıdır; layout analizi, tablo tespiti, çok sütun okuma sırası ve Markdown dönüşümü bu pipeline üzerinden sağlanmalıdır.
13. THE Surya adaptörü SHALL Surya OCR 2 modelini kullanmalıdır; layout analizi, OCR ve tablo tespiti tek bir VLM üzerinden yürütülmelidir.
14. THE PythonOcrMicroservice'in URL'i ve OcrServiceApiKey'i THE Sistem SHALL yapılandırma dosyasından okumalıdır.

---

### Requirement 5: Koordinat Eşleştirmesiyle Clause İçeriği Derleme

**User Story:** Bir analist olarak, her maddenin metin içeriğinin OCR koordinatları ile BoundingBox tespiti çakıştırılarak derlenmesini istiyorum; böylece clause görüntüsü ile metni her zaman aynı bölgeyi temsil etsin ve kayma yaşanmasın.

#### Acceptance Criteria

1. WHEN PageLevelOcr tamamlandığında, THE Madde_Tespit_Motoru SHALL her clause için ClauseCoordinateMapping sürecini yürütmelidir: o clause'un BoundingBox koordinat aralığına düşen OCR bloklarını seçerek clause'un metin içeriğini derlemelidir.
2. THE Madde_Tespit_Motoru SHALL ClauseCoordinateMapping sırasında OCR koordinatları ile BoundingBox tespiti sonuçlarını çakıştırarak clause sınırlarını belirlemelidir.
3. THE Madde_Tespit_Motoru SHALL clause-image kırpma işlemini PageLevelOcr tamamlandıktan sonra gerçekleştirmeli; kırpma koordinatları BoundingBox değerlerine BboxMargin eklenerek hesaplanmalıdır.
4. THE Madde_Tespit_Motoru SHALL aynı sayfada birden fazla clause bulunduğunda her clause için yalnızca o clause'un koordinat aralığına düşen OCR bloklarını seçmelidir.
5. IF bir clause'un koordinat aralığında hiçbir OCR bloğu bulunamazsa, THEN THE Madde_Tespit_Motoru SHALL ilgili clause'un içerik alanını boş bırakmalı ve bu durumu günlüğe kaydetmelidir.
6. THE Madde_Tespit_Motoru SHALL tablo yapılarını, paragrafları ve metin bloklarını ClauseCoordinateMapping sürecinde ayrı elementler olarak korumalıdır.

---

### Requirement 6: İmza Tespiti ve İmzacı Bilgilerinin Çıkarılması

**User Story:** Bir analist olarak, sözleşmedeki imzaların ve imzacı isimlerinin otomatik olarak tespit edilmesini istiyorum; böylece sözleşmeyi kimin imzaladığını doğrulayabileyim.

#### Acceptance Criteria

1. WHEN PDF görüntüleri işlendiğinde, THE İmza_Tespit_Motoru SHALL sözleşmedeki imza alanlarını tespit etmelidir.
2. THE İmza_Tespit_Motoru SHALL her imza alanı için imza görüntüsünü BoundingBox bilgisine göre kırpmalı ve Base64 formatında kodlamalıdır.
3. THE İmza_Tespit_Motoru SHALL her imza alanının yakınındaki imzacı adını OCR aracılığıyla okumalıdır.
4. IF bir imzacı adı tespit edilemiyorsa, THEN THE İmza_Tespit_Motoru SHALL imzacı adı alanını boş bırakmalı ve tespit edilemediğini günlüğe kaydetmelidir.
5. THE İmza_Tespit_Motoru SHALL tespit edilen tüm imzacıları liste halinde (ad + imaj çifti olarak) üretmelidir.

---

### Requirement 7: Taranmış PDF Analiz Çıktısı (JSON Üretimi)

**User Story:** Bir analist olarak, taranmış PDF'nin işlenmesi sonucunda standart bir JSON çıktısı elde etmek istiyorum; böylece sonraki analiz adımlarında kullanabileyim.

#### Acceptance Criteria

1. WHEN tüm madde tespiti, OCR ve imza tespiti tamamlandığında, THE Rapor_Üretici SHALL aşağıdaki yapıda geçerli bir JSON çıktısı üretmelidir:
   - `title`: Sözleşme başlığı (string)
   - `clauses`: Madde numaralarını anahtar olarak kullanan nesne; her değer `content` (Markdown string) ve `image` (Base64 string) alanlarını içermelidir.
   - `signers`: Her elemanın `name` (string) ve `image` (Base64 string) alanlarını içerdiği dizi
   - `additional`: Her elemanın `type` (string) ve `content` (Markdown string) alanlarını içerdiği dizi
2. THE Rapor_Üretici SHALL üretilen JSON'un geçerli JSON sözdizimini karşıladığını doğrulamalıdır.
3. THE Rapor_Üretici SHALL `clauses` nesnesinde madde anahtarlarını doğal sıralamaya göre (1, 1.1, 1.2, 2, 2.1 …) düzenlemelidir.
4. IF sözleşmede ek protokol veya ek bölüm tespit edilirse, THE Rapor_Üretici SHALL bu içerikleri `additional` dizisine uygun `type` etiketiyle eklemelidir.
5. FOR ALL geçerli JSON çıktıları, THE Rapor_Üretici SHALL JSON içeriğini serileştirip (serialize) yeniden ayrıştırdığında (deserialize) eşdeğer bir veri yapısı elde etmelidir (round-trip özelliği).

---

### Requirement 8: Word (.docx) Dosyası Yükleme ve Doğrulama

**User Story:** Bir analist olarak, orijinal sözleşmenin Word dosyasını sisteme yüklemek istiyorum; böylece imzalı versiyonla karşılaştırma yapılabilsin.

#### Acceptance Criteria

1. THE Sistem SHALL orijinal sözleşme dosyalarını yalnızca .docx formatında kabul etmelidir.
2. WHEN bir .docx dosyası yüklendiğinde, THE DOCX_İşleyici SHALL dosyanın geçerli bir Word belgesi olup olmadığını doğrulamalıdır.
3. IF yüklenen dosya .docx formatında değilse, THEN THE Sistem SHALL kullanıcıya "Geçersiz dosya formatı: yalnızca .docx kabul edilmektedir" hata mesajını döndürmelidir.
4. IF yüklenen .docx dosyası bozuksa veya okunamıyorsa, THEN THE DOCX_İşleyici SHALL kullanıcıya dosya bozukluğunu belirten bir hata mesajı döndürmelidir.

---

### Requirement 9: Word Dosyasından İçerik Çıkarımı

**User Story:** Bir analist olarak, Word dosyasındaki sözleşme maddelerinin otomatik olarak ayrıştırılmasını istiyorum; böylece yapısal karşılaştırma yapılabilsin.

#### Acceptance Criteria

1. WHEN geçerli bir .docx dosyası yüklendiğinde, THE DOCX_İşleyici SHALL belge içeriğini Apache POI kullanarak işlemelidir.
2. THE DOCX_İşleyici SHALL madde sınırlarını paragraf stili (Heading, Normal vb.) ile madde numarası regex örüntüsünün (örn: "1.", "1.1", "MADDE 1", "Madde 1.2") birlikte eşleşmesine göre belirlemeli; her eşleşmede yeni bir clause başlatmalıdır.
3. THE DOCX_İşleyici SHALL iç içe numaralandırma hiyerarşisini (1 → 1.1 → 1.1.1) korumalıdır.
4. THE DOCX_İşleyici SHALL her maddeyi Markdown formatında üretmelidir; tablo, paragraf ve liste yapıları Markdown sözdizimine dönüştürülmelidir.
5. THE DOCX_İşleyici SHALL sözleşme başlığını belgenin üst bölümünden çıkarmalıdır.
6. THE DOCX_İşleyici SHALL ek protokol veya ek bölüm içeriklerini tespit edip `additional` yapısına uygun biçimde çıkarmalıdır.
7. WHEN DOCX içerik çıkarımı tamamlandığında, THE Rapor_Üretici SHALL aşağıdaki yapıda geçerli bir JSON çıktısı üretmelidir:
   - `title`: Sözleşme başlığı (string)
   - `clauses`: Madde numaralarını anahtar olarak kullanan nesne; her değer `content` (Markdown string) alanını içermelidir.
   - `additional`: Her elemanın `type` ve `content` alanlarını içerdiği dizi

---

### Requirement 10: Madde Bazlı Diff Analizi (java-diff-utils)

**User Story:** Bir analist olarak, imzalı sözleşmedeki her maddenin orijinal Word içeriğiyle metin farkının otomatik olarak çıkarılmasını istiyorum; böylece değiştirilmiş kısımlar tespit edilebilsin.

#### Acceptance Criteria

1. WHEN imzalı PDF JSON çıktısı ve DOCX JSON çıktısı hazır olduğunda, THE Diff_Motoru SHALL her madde için OCR'dan elde edilen Markdown içeriği ile DOCX'ten elde edilen Markdown içeriğini java-diff-utils kullanarak karşılaştırmalıdır; bu karşılaştırma AI semantik karşılaştırmasıyla paralel olarak yürütülmelidir.
2. THE Diff_Motoru SHALL her madde karşılaştırması için `changes` (boolean) ve `result` (Markdown formatında fark açıklaması) alanlarını üretmeli; `result` alanı geçerli Markdown sözdiziminde olmalıdır; bu sonuç analiz raporunda `diff` alanında yer almalıdır.
3. WHILE iki içerik arasında herhangi bir fark yokken, THE Diff_Motoru SHALL `changes` alanını `false` olarak ve `result` alanını boş bırakmalıdır.
4. WHEN iki içerik arasında fark tespit edildiğinde, THE Diff_Motoru SHALL `changes` alanını `true` olarak ve `result` alanına farkı açıklayan metni yazmalıdır.
5. IF bir madde yalnızca imzalı versiyonda mevcutsa, THEN THE Diff_Motoru SHALL bu maddeyi "yalnızca imzalı versiyonda mevcut" olarak işaretlemelidir.
6. IF bir madde yalnızca orijinal Word versiyonunda mevcutsa, THEN THE Diff_Motoru SHALL bu maddeyi "yalnızca orijinal versiyonda mevcut" olarak işaretlemelidir.

---

### Requirement 11: Yapay Zeka Destekli Semantik Karşılaştırma

**User Story:** Bir analist olarak, madde içeriklerinin yapay zeka tarafından semantik düzeyde karşılaştırılmasını istiyorum; böylece yalnızca metin farkı değil, anlam değişiklikleri de tespit edilebilsin.

#### Acceptance Criteria

1. THE Sistem SHALL AI semantik karşılaştırma işlemini AiAdapter arayüzü üzerinden yürütmelidir; kullanılacak adaptör süreç başında yapılandırma dosyasından seçilmelidir.

2. THE AiAdapter arayüzü aşağıdaki servisler tarafından implement edilmelidir:
   - **OpenAI**: OpenAI API üzerinden GPT modelleri
   - **AzureFoundryAI**: Azure AI Foundry üzerinden deploy edilmiş modeller
   - **ClaudeAI**: Anthropic Claude API
   - **OpenRouterAI**: OpenRouter API üzerinden erişilebilen herhangi bir model

3. THE Sistem SHALL AiAdapter'ı dışarıdan kullanan tüm bileşenler açısından hangi adaptörün kullanıldığından bağımsız, aynı arayüzde çalışacak şekilde tasarlanmalıdır.

4. WHEN imzalı PDF JSON çıktısı ve DOCX JSON çıktısı hazır olduğunda, THE AI_Karşılaştırıcı SHALL her madde için imzalı ve orijinal içerikleri seçili AiAdapter üzerinden semantik karşılaştırmaya tabi tutmalıdır; bu işlem Diff_Motoru'nun metin karşılaştırmasıyla paralel olarak yürütülmeli ve birbirini beklememelidir.

5. THE AI_Karşılaştırıcı SHALL karşılaştırma sonucunda `changes` (boolean) ve `result` (Markdown formatında yapay zeka değerlendirme metni) alanlarını üretmeli; `result` alanı geçerli Markdown sözdiziminde olmalıdır; bu sonuç analiz raporunda `diff_ai` alanında yer almalıdır.

6. THE Sistem SHALL her AiAdapter'ın endpoint, api-key, model adı gibi yapılandırma parametrelerini yapılandırma dosyasından okumalıdır.

7. IF AiAdapter harici bir servise erişemiyorsa, THEN THE AI_Karşılaştırıcı SHALL `changes` alanını `null` olarak ve `result` alanına erişim hatasını belirten mesajı yazmalıdır.

8. THE AI_Karşılaştırıcı SHALL sözleşme maddesi metinlerini harici sistemlere güvenli iletişim protokolü (HTTPS) ile göndermelidir.

9. FOR ALL geçerli AiAdapter kullanımları, THE Sistem SHALL aynı iki madde içeriği için aynı AiAdapter ve model ile yapılan karşılaştırmanın tutarlı bir `changes` sonucu üretmesini sağlamalıdır.

---

### Requirement 12: Analiz Raporu Üretimi (JSON)

**User Story:** Bir analist olarak, tüm karşılaştırma sonuçlarının tek bir JSON raporu olarak elde edilmesini istiyorum; böylece değişiklikleri bütünsel olarak inceleyebileyim.

#### Acceptance Criteria

1. WHEN tüm madde bazlı diff ve AI karşılaştırmaları tamamlandığında, THE Rapor_Üretici SHALL aşağıdaki yapıda geçerli bir JSON analiz raporu üretmelidir:
   - `analyze_clauses`: Madde numaralarını anahtar olarak kullanan nesne; her değer şu alanları içermelidir:
     - `signed_image`: İmzalı madde görüntüsünün Base64 kodlaması
     - `signed_content`: İmzalı maddenin Markdown içeriği
     - `original_content`: Orijinal Word maddesinin Markdown içeriği
     - `diff`: java-diff-utils sonucunu içeren nesne; `changes` (boolean) ve `result` (string) alanlarından oluşur; `result` alanı Markdown formatındadır
     - `diff_ai`: AI semantik karşılaştırma sonucunu içeren nesne; `changes` (boolean) ve `result` (string) alanlarından oluşur; `result` alanı Markdown formatındadır
2. THE Rapor_Üretici SHALL `diff` ve `diff_ai` alanlarının her ikisinin de analiz raporunda bağımsız alanlar olarak yer almasını sağlamalıdır.
3. THE Rapor_Üretici SHALL üretilen analiz JSON'unun geçerli JSON sözdizimini karşıladığını doğrulamalıdır.
4. THE Rapor_Üretici SHALL `analyze_clauses` nesnesindeki anahtarları doğal madde sıralamasına göre düzenlemelidir.
5. IF bir madde için imzalı versiyonda görüntü bulunmuyorsa, THEN THE Rapor_Üretici SHALL `signed_image` alanını `null` olarak bırakmalıdır.
6. FOR ALL geçerli analiz JSON çıktıları, THE Rapor_Üretici SHALL JSON içeriğini serileştirip (serialize) yeniden ayrıştırdığında (deserialize) eşdeğer bir veri yapısı elde etmelidir (round-trip özelliği).

---

### Requirement 13: Hata Yönetimi ve Günlük Kaydı

**User Story:** Bir sistem yöneticisi olarak, sistem hatalarının ve uyarılarının kayıt altına alınmasını istiyorum; böylece sorunları tespit edip giderebileyim.

#### Acceptance Criteria

1. WHEN herhangi bir bileşende bir hata meydana geldiğinde, THE Sistem SHALL hatayı zaman damgası, bileşen adı ve hata mesajı ile birlikte günlük dosyasına kaydetmelidir.
2. THE Sistem SHALL günlük kayıtlarını en az üç seviyede (INFO, WARN, ERROR) desteklemelidir.
3. IF bir bileşen kritik olmayan bir hatayla karşılaşırsa, THEN THE Sistem SHALL işlemi durdurmaksızın hatayı WARN seviyesinde kaydedip diğer adımlarla devam etmelidir.
4. IF bir bileşen kritik bir hatayla karşılaşırsa, THEN THE Sistem SHALL işlemi ERROR seviyesinde kaydedip kullanıcıya açıklayıcı bir hata yanıtı döndürmelidir.

---

### Requirement 14: Performans Gereksinimleri

**User Story:** Bir analist olarak, makul boyuttaki sözleşmelerin analiz sürecinin kabul edilebilir bir sürede tamamlanmasını istiyorum; böylece iş verimliliğim korunabilsin.

#### Acceptance Criteria

1. WHEN 50 sayfaya kadar olan bir PDF dosyası yüklendiğinde, THE Sistem SHALL PDF görüntüye dönüştürme ve madde tespit işlemlerini 120 saniye içinde tamamlamalıdır.
2. WHEN 50 sayfaya kadar olan bir PDF için PageLevelOcr ve Markdown dönüşüm işlemleri yürütüldüğünde, THE Sistem SHALL bu işlemleri 180 saniye içinde tamamlamalıdır.
3. WHEN diff analizi ve AI karşılaştırması 100 maddeye kadar olan bir sözleşme için paralel olarak çalıştırıldığında, THE Sistem SHALL analiz raporunu 60 saniye içinde üretmelidir.

---

### Requirement 15: Yapılandırılabilir Bileşenler

**User Story:** Bir sistem yöneticisi olarak, OCR adaptörü ve AI servis bağlantıları gibi bileşenlerin yapılandırma dosyasından yönetilebilmesini istiyorum; böylece farklı ortamlara kolayca uyum sağlayabileyim.

#### Acceptance Criteria

1. THE Sistem SHALL kullanılacak OcrAdapter türünü (`azure-cognitive-service`, `microsoft-foundry`, `mistral-ocr`, `paddleocr`, `surya`) yapılandırma dosyasından okumalıdır; `paddleocr` ve `surya` adaptörleri PythonOcrMicroservice üzerinden çalışır, diğerleri doğrudan HTTP API çağrısı yapar.
2. THE Sistem SHALL seçilen OcrAdapter'ın yapılandırma parametrelerini (endpoint, api-key, dil ayarı vb.) yapılandırma dosyasından okumalıdır.
3. THE Sistem SHALL BboxMargin değerini yapılandırma dosyasından okumalıdır; belirtilmemişse her yön için 5px varsayılan değerini kullanmalıdır.
4. THE Sistem SHALL kullanılacak AiAdapter türünü (`openai`, `azure-foundry-ai`, `claude-ai`, `openrouter-ai`) ve her adaptörün endpoint, api-key, model adı gibi parametrelerini yapılandırma dosyasından okumalıdır.
5. WHERE bir yapılandırma parametresi belirtilmemişse, THE Sistem SHALL ilgili bileşen için önceden tanımlanmış varsayılan değerleri kullanmalıdır.
6. THE Sistem SHALL ReportMicroservice'in URL'ini ve ReportServiceApiKey'ini yapılandırma dosyasından okumalıdır.
7. IF bir yapılandırma dosyası okunamıyorsa veya zorunlu parametre eksikse, THEN THE Sistem SHALL başlatma sırasında hatayı açıklayıcı mesajla kullanıcıya bildirmelidir.

---

### Requirement 16: ContractAnalyzer Java Public API

**User Story:** Bir Java geliştiricisi olarak, tüm analiz sürecini tek bir `ContractAnalyzer` sınıfı üzerinden yönetmek istiyorum; böylece karmaşık iç bileşenleri bilmeden basit bir API ile entegrasyon yapabileyim.

#### Acceptance Criteria

1. THE Sistem SHALL `ContractAnalyzer` adında public bir Java sınıfı sunmalıdır; bu sınıf tüm analiz sürecini kapsülleyen ana giriş noktasıdır.

2. THE `ContractAnalyzer` sınıfı SHALL parametresiz constructor ile örneklenebilmelidir:
   ```java
   ContractAnalyzer analyzer = new ContractAnalyzer();
   ```

3. THE `ContractAnalyzer` sınıfı SHALL her method'u `ContractAnalyzer` döndürerek method chaining (Fluent API) desteklemelidir:
   ```java
   analyzer.config(new JSONObject().put("OCR", "AzureCognitiveService"))
           .setSignedContractPdf("c:\\path\\to\\signed-contract.pdf")
           .setOriginalContractDoc("c:\\path\\to\\original-contract.docx")
           .start();
   ```

4. THE `config(JSONObject config)` method'u SHALL en az aşağıdaki yapılandırma anahtarlarını kabul etmelidir:
   - `"OCR"`: Kullanılacak OcrAdapter türü (örn: `"AzureCognitiveService"`, `"MicrosoftFoundry"`, `"MistralOcr"`, `"PaddleOCR"`, `"Surya"`)
   - `"OCR_CONFIG"`: Seçilen adaptörün endpoint, api-key gibi ek parametrelerini içeren iç içe JSONObject
   - `"BBOX_MARGIN"`: Kırpma için kenar boşluğu (piksel); belirtilmezse 5px varsayılan
   - `"AI"`: Kullanılacak AiAdapter türü (örn: `"OpenAI"`, `"AzureFoundryAI"`, `"ClaudeAI"`, `"OpenRouterAI"`)
   - `"AI_CONFIG"`: Seçilen adaptörün endpoint, api-key, model adı gibi ek parametrelerini içeren iç içe JSONObject

5. THE `setSignedContractPdf(String filePath)` method'u SHALL imzalı taranmış PDF dosyasının yolunu kabul etmelidir; dosya mevcut değilse `IllegalArgumentException` fırlatmalıdır.

6. THE `setOriginalContractDoc(String filePath)` method'u SHALL orijinal sözleşmenin .docx dosyasının yolunu kabul etmelidir; dosya mevcut değilse `IllegalArgumentException` fırlatmalıdır.

7. THE `start()` method'u SHALL tüm analiz pipeline'ını senkron olarak çalıştırmalıdır:
   - Adım 1: PDF işleme ve madde tespiti (BBox + margin)
   - Adım 2: Page-level OCR ve ClauseCoordinateMapping
   - Adım 3: İmza tespiti
   - Adım 4: Word dosyası işleme ve clause çıkarımı
   - Adım 5: Metin diff ve AI semantik karşılaştırma (paralel)
   - Tüm adımlar tamamlanmadan `start()` dönmemelidir.

8. THE `start()` method'u SHALL pipeline içinde kritik bir hata oluşursa `ContractAnalysisException` fırlatmalıdır.

9. THE `getResultJson()` method'u SHALL tüm analiz sonucunu tek bir `JSONObject` olarak döndürmelidir; yapı:
   ```json
   {
     "signed_contract": { ... },
     "original_contract": { ... },
     "analysis": { "analyze_clauses": { ... } }
   }
   ```

10. THE `getSignedContractJson()` method'u SHALL yalnızca imzalı sözleşmenin JSON çıktısını (`title`, `clauses`, `signers`, `additional`) döndürmelidir.

11. THE `getOriginalContractJson()` method'u SHALL yalnızca orijinal Word sözleşmenin JSON çıktısını (`title`, `clauses`, `additional`) döndürmelidir.

12. THE `getAnalysisJson()` method'u SHALL yalnızca diff analiz raporunu (`analyze_clauses`) döndürmelidir.

13. IF `start()` henüz çağrılmamışsa veya başarısız olduysa, THEN `getResultJson()`, `getSignedContractJson()`, `getOriginalContractJson()` ve `getAnalysisJson()` method'ları SHALL `null` döndürmelidir.

14. FOR ALL geçerli `ContractAnalyzer` kullanımları, THE `start()` method'u çağrıldıktan sonra `getResultJson()` çağrısı SHALL her seferinde aynı `JSONObject` içeriğini döndürmelidir (idempotent).

---

### Requirement 17: HTML Rapor Üretimi

**User Story:** Bir Java geliştiricisi olarak, analiz sonuçlarını tablo formatında bir HTML dosyası olarak dışa aktarmak istiyorum; böylece tarayıcıda kolayca inceleyebileyim.

#### Acceptance Criteria

1. THE `ContractAnalyzer` sınıfı SHALL `exportHtml(String outputFilePath)` adında bir method sunmalıdır; bu method `start()` tamamlandıktan sonra çağrılabilmelidir.

2. THE `exportHtml(String outputFilePath)` method'u SHALL `getResultJson()` çıktısını ReportMicroservice'in `/report/html` endpoint'ine HTTP POST olarak göndermeli ve dönen HTML içeriğini belirtilen dosya yoluna yazmalıdır.

3. THE ReportMicroservice `/report/html` endpoint'i SHALL gelen `analyze.json` verisini tek bir ReportTemplate (`report-template.html`) üzerinden işleyerek Rapor Tablosunu içeren geçerli HTML5 dökümanını yanıt gövdesinde döndürmelidir; Content-Type `text/html; charset=UTF-8` olmalıdır.

4. THE ReportMicroservice SHALL `/report/html` endpoint'inde her istekte HTTP Authorization header üzerinden ReportServiceApiKey doğrulaması yapmalıdır; geçersiz veya eksik anahtar için 401 Unauthorized döndürmelidir.

5. THE ReportTemplate SHALL şu kolonları içeren Rapor Tablosunu tanımlamalıdır (soldan sağa):
   - **Madde No**: Clause numarası
   - **Clause Image**: Base64 kodlu `<img>` tag'i olarak gömülmüş clause görüntüsü
   - **Signed Content**: Markdown'dan HTML'e dönüştürülmüş imzalı madde içeriği
   - **Original Content**: Markdown'dan HTML'e dönüştürülmüş orijinal madde içeriği
   - **Java Diff**: `changes: true` ise kırmızı, `changes: false` ise yeşil arka planla gösterilen; result Markdown'dan HTML'e render edilen diff sonucu
   - **AI Diff**: `changes: true` ise kırmızı, `changes: false` ise yeşil arka planla gösterilen; result Markdown'dan HTML'e render edilen AI diff sonucu

6. THE ReportTemplate SHALL başlık bölümünde sözleşme başlığını ve rapor oluşturma tarih/saatini içermelidir.

7. THE ReportMicroservice SHALL tablo satırlarını doğal madde sıralamasına (1, 1.1, 1.2, 2, 2.1 …) göre düzenlemelidir.

8. IF `start()` henüz çağrılmamışsa veya başarısız olduysa, THEN `exportHtml()` method'u SHALL `IllegalStateException` fırlatmalıdır.

9. THE `exportHtml(String outputFilePath)` method'u SHALL `ContractAnalyzer` döndürerek method chaining desteklemelidir.

---

### Requirement 18: PDF Rapor Üretimi

**User Story:** Bir Java geliştiricisi olarak, analiz sonuçlarını tablo formatında bir PDF dosyası olarak dışa aktarmak istiyorum; böylece paylaşılabilir bir belge elde edebileyim.

#### Acceptance Criteria

1. THE `ContractAnalyzer` sınıfı SHALL `exportPdf(String outputFilePath)` adında bir method sunmalıdır; bu method `start()` tamamlandıktan sonra çağrılabilmelidir.

2. THE `exportPdf(String outputFilePath)` method'u SHALL `getResultJson()` çıktısını ReportMicroservice'in `/report/pdf` endpoint'ine HTTP POST olarak göndermeli ve dönen PDF binary içeriğini belirtilen dosya yoluna yazmalıdır.

3. THE ReportMicroservice `/report/pdf` endpoint'i SHALL önce `/report/html` ile aynı ReportTemplate'i kullanarak HTML üretmeli, ardından bu HTML'i WeasyPrint veya Playwright ile PDF'e dönüştürüp binary yanıt gövdesinde döndürmelidir; Content-Type `application/pdf` olmalıdır.

4. THE ReportMicroservice SHALL `/report/pdf` endpoint'inde her istekte HTTP Authorization header üzerinden ReportServiceApiKey doğrulaması yapmalıdır; geçersiz veya eksik anahtar için 401 Unauthorized döndürmelidir.

5. THE ReportMicroservice SHALL `/report/pdf` üretiminde ayrı bir şablon kullanmamalı; HTML ve PDF çıktıları için tek ve aynı ReportTemplate kullanılmalıdır.

6. THE ReportMicroservice SHALL PDF raporunda ReportTemplate'deki HTML tablosuyla aynı kolonları içermelidir (Madde No, Clause Image, Signed Content, Original Content, Java Diff, AI Diff).

7. THE ReportMicroservice SHALL clause görüntülerini PDF içine gömülü olarak üretmelidir.

8. THE ReportMicroservice SHALL tablo satırlarını doğal madde sıralamasına göre düzenlemelidir.

9. IF `start()` henüz çağrılmamışsa veya başarısız olduysa, THEN `exportPdf()` method'u SHALL `IllegalStateException` fırlatmalıdır.

10. THE `exportPdf(String outputFilePath)` method'u SHALL `ContractAnalyzer` döndürerek method chaining desteklemelidir.

---

### Requirement 19: Proje Yapısı ve Build Sistemi

**User Story:** Bir Java geliştiricisi olarak, projenin Gradle ile yönetilen, Java 21 kullanan ve bağımlılıkları net tanımlanmış bir library JAR olarak paketlenmesini istiyorum; böylece başka projelere kolayca entegre edebileyim.

#### Acceptance Criteria

1. THE Proje SHALL Gradle build sistemi ile yönetilmelidir; `build.gradle` veya `build.gradle.kts` dosyası proje kökünde bulunmalıdır.

2. THE Proje SHALL Java 21 ile derlenmeli ve çalışmalıdır; `build.gradle` dosyasında `sourceCompatibility` ve `targetCompatibility` Java 21 olarak ayarlanmalıdır.

3. THE Proje SHALL bir library JAR olarak paketlenmelidir; `gradle build` komutu ile `ContractAnalyzer` sınıfını içeren bir JAR dosyası üretilmelidir.

4. THE `build.gradle` dosyası SHALL en az aşağıdaki bağımlılıkları içermelidir:
   - `org.json:json` — JSON işleme (JSONObject API)
   - `net.sourceforge.tess4j:tess4j` — Tesseract OCR adaptörü (Tesseract adaptörü için; PaddleOCR ve Surya Python microservis üzerinden çalışır)
   - `org.apache.pdfbox:pdfbox` — PDF işleme ve sayfa görüntüsüne dönüştürme
   - `org.apache.poi:poi-ooxml` — Word (.docx) dosyası işleme (Apache POI)
   - `io.github.java-diff-utils:java-diff-utils` — Metin diff analizi
   - `com.vladsch.flexmark:flexmark-all` — Markdown'dan HTML'e dönüşüm (HTML raporu için — report microservisi kullanılmıyorsa fallback olarak)

5. THE Proje SHALL `samples/sample-01/signed.pdf` ve `samples/sample-01/original.docx` dosyalarını integration test verisi olarak kullanmalıdır; bu dosyaların yolu `build.gradle` içinde test kaynağı olarak referanslanmalıdır.

6. THE Proje SHALL `src/main/java` ve `src/test/java` standart Gradle kaynak dizin yapısını kullanmalıdır.

7. THE Proje SHALL `gradle test` komutu ile tüm birim ve entegrasyon testlerini çalıştırabilmelidir.

---

### Requirement 20: Dil Tespiti ve Çok Dilli Kolon Analizi

**User Story:** Bir analist olarak, sözleşmenin dil yapısının ve sayfa düzeninin PDF yüklendikten sonra otomatik olarak tespit edilmesini istiyorum; böylece tek dilli ve çok dilli (dikey kolon düzenli) sözleşmeler doğru şekilde işlenebilsin.

#### Acceptance Criteria

1. WHEN bir PDF dosyası yüklendiğinde ve sayfa görüntüleri hazırlandığında, THE DilTespitMotoru SHALL PDF işleminin ilk adımı olarak dil ve kolon analizini otomatik çalıştırmalıdır; bu analiz tamamlanmadan OCR işlemi başlamamalıdır.

2. THE DilTespitMotoru SHALL her sayfa için şu analizi gerçekleştirmelidir:
   - Sayfanın tek sütunlu mu yoksa çok sütunlu mu olduğunu tespit etmeli
   - Tespit edilen her sütunun DikilKolonBBox koordinatlarını (x_start, x_end, y_start, y_end) çıkarmalı
   - Her sütundaki metnin dilini tanımlamalı (örn: `tr`, `en`, `de`, `fr` vb.)

3. WHEN sözleşme tek dilli olarak tespit edildiğinde, THE DilTespitMotoru SHALL:
   - Tespit edilen dili AktifDil olarak kaydetmeli
   - OCR_Motoru'nun dil parametresini otomatik olarak bu dile ayarlamalı

4. WHEN sözleşme çok dilli (dikey kolon düzenli) olarak tespit edildiğinde, THE DilTespitMotoru SHALL:
   - Her sütunun DikilKolonBBox koordinatlarını ve dilini kaydetmeli
   - OCR_Motoru'na her sütun için ayrı ayrı OCR çalıştırma talimatı vermeli; her sütun kendi diliyle işlenmeli
   - Madde_Tespit_Motoru'na sütun sınırlarını bildirmeli; bbox tespiti sütun koordinatları içinde kalmaya zorlanmalı

5. THE DilTespitMotoru SHALL çok dilli sözleşmelerde AktifDil'i belirlemek için şu önceliği kullanmalıdır:
   - Config'de `PRIMARY_LANGUAGE` parametresi tanımlanmışsa onu kullanmalı
   - Tanımlanmamışsa sayfada en fazla alan kaplayan sütunun dilini AktifDil olarak seçmeli

6. THE DilTespitMotoru SHALL analiz sonucunu aşağıdaki yapıda JSON olarak üretmeli ve pipeline boyunca tüm bileşenlere iletmelidir:
   ```json
   {
     "multilingual": true,
     "active_language": "tr",
     "columns": [
       { "index": 0, "language": "tr", "bbox": { "x_start": 0, "x_end": 400, "y_start": 0, "y_end": 2339 } },
       { "index": 1, "language": "en", "bbox": { "x_start": 420, "x_end": 820, "y_start": 0, "y_end": 2339 } }
     ]
   }
   ```

7. WHEN çok dilli KolonDüzeni tespit edildiğinde, THE Madde_Tespit_Motoru SHALL ClauseCoordinateMapping sırasında yalnızca AktifDil sütununun DikilKolonBBox koordinatları içinde kalan OCR bloklarını clause içeriğine dahil etmelidir; diğer dil sütunlarının blokları dahil edilmemelidir.

8. WHEN çok dilli KolonDüzeni tespit edildiğinde, THE AI_Karşılaştırıcı SHALL diff prompt'una sözleşmenin çok dilli olduğunu ve AktifDil'in ne olduğunu belirten bir sistem notu eklemelidir.

9. IF DilTespitMotoru dil veya kolon yapısını belirleyemiyorsa, THEN THE DilTespitMotoru SHALL varsayılan olarak tek dilli ve tek sütunlu düzen kabul etmeli; bu durumu WARN seviyesinde günlüğe kaydetmeli ve işlemi sürdürmelidir.

10. THE Sistem SHALL config'de `PRIMARY_LANGUAGE` parametresini desteklemelidir; bu parametre belirtildiğinde DilTespitMotoru otomatik tespiti bu değerle geçersiz kılabilir.

11. FOR ALL geçerli PDF girişleri, THE DilTespitMotoru SHALL dil analizi sonucunu `getResultJson()` çıktısının `signed_contract` nesnesine `language_analysis` alanı olarak eklemeli; bu alan `multilingual` (boolean), `active_language` (string) ve `columns` (dizi) bilgilerini içermelidir.

# Kasa

Çok kiracılı (multi-tenant) ödeme altyapısı platformu. İşletmeler API anahtarıyla sisteme bağlanır, ödeme alır, iade yapar ve kazançlarını çeker. Her para hareketi çift taraflı muhasebe defterine (double-entry ledger) yazılır.

> Eğitim ve portföy projesidir. Gerçek kart verisi işlemez; banka tarafı simüle edilir.

## Çözülen mühendislik problemleri

- **Para kaybolmaz:** Her kayıtta borç ve alacak toplamı eşittir. Bu kural yalnızca uygulamada değil, veritabanında da zorunludur.
- **Geçmiş değiştirilemez:** Defter kayıtları güncellenemez ve silinemez. Hatalar ters kayıtla düzeltilir.
- **Çift işlem olmaz:** Aynı istek eşzamanlı olarak tekrar gönderilse bile idempotency anahtarı sayesinde bir kez işlenir.
- **Bakiye aşılamaz:** Eşzamanlı para çekme istekleri satır kilitleriyle sıraya girer. Hesaplar sabit sırada kilitlendiği için deadlock oluşmaz.
- **Tutarsızlık yakalanır:** Mutabakat sorgusu, saklanan bakiyeleri defter kayıtlarının toplamıyla karşılaştırır.
- **Kiracılar birbirinin verisini göremez:** İzolasyonu PostgreSQL Row Level Security uygular. Bir sorguda `tenant_id` filtresi unutulsa bile başka işletmenin verisi dönmez; kiracı bağlanmamışsa hiçbir satır görünmez.
- **Uygulama en az yetkiyle çalışır:** `kasa_app` rolü defter kayıtlarını değiştiremez. Bu rol RLS'i aşabilecek yetkilere sahipse uygulama hiç başlamaz.
- **Kimlik bilgileri korunur:** Parolalar BCrypt ile saklanır. API anahtarlarının yalnızca SHA-256 özeti tutulur ve anahtarın tamamı bir kez gösterilir. Sızan bir API anahtarı yeni anahtar üretemez.
- **Banka yanıt vermese de para kaybolmaz:** Her banka çağrısından önce işlem `PENDING` olarak kaydedilir. Zaman aşımında sonuç tahmin edilmez; kurtarma işi bankaya sorup işlemi sonuçlandırır.
- **Banka beklenirken veritabanı bağlantısı tutulmaz:** Akış üç adımdır: işlemi kaydet, transaction dışında bankayı çağır, sonucu kaydet.
- **Capture ve void yarışamaz:** Satır kilidi ve "ödeme başına tek `PENDING` işlem" kısıtı, aynı anda tek banka çağrısına izin verir.
- **Geçersiz durum geçişi imkânsızdır:** Örneğin `CAPTURED` bir ödeme yeniden `AUTHORIZED` olamaz. Bu kuralı veritabanı trigger'ı uygular.
- **Kart numarası sisteme giremez:** API yalnızca token kabul eder.
- **Bildirim kaybolmaz:** Webhook, ödeme sonucuyla aynı transaction'da kuyruğa yazılır (transactional outbox). Teslim edilemezse üstel geri çekilmeyle yeniden denenir.
- **Birden çok sunucu aynı bildirimi almaz:** `FOR UPDATE SKIP LOCKED` ile her gönderici farklı satırları alır. Gönderici çökerse lease süresi dolunca teslimat başkası tarafından devralınır.
- **Sahte bildirim ayırt edilir:** Her webhook, zaman damgasıyla birlikte HMAC-SHA256 ile imzalanır.
- **Webhook URL'si iç ağa saldırı aracı olamaz (SSRF):** Özel ağ, loopback ve bulut metadata adresleri hem kayıtta hem her gönderimde engellenir; yönlendirmeler izlenmez.

## Teknolojiler

Java 21, Spring Boot 4, Spring Security, JWT, PostgreSQL 18, Flyway, JUnit, embedded PostgreSQL. Mimari modüler monolittir.

## Testleri çalıştırma

Testler gerçek bir PostgreSQL üzerinde çalışır ve Docker gerektirmez. İlk çalıştırmada bağımlılıkların inmesi birkaç dakikadan uzun sürebilir.

```bash
cd backend
mvn test
```

## Uygulamayı çalıştırma

Docker gerektirir. `KASA_JWT_SECRET` en az 32 bayt olmalıdır; tanımlı değilse uygulama başlamaz. Aşağıdaki PowerShell komutları yalnızca yerel geliştirme içindir.

```powershell
docker compose up -d
cd backend
$env:KASA_JWT_SECRET = "yerel-gelistirme-icin-en-az-32-baytlik-anahtar"
mvn spring-boot:run
```

## API

| Metot | Yol | Kimlik doğrulama | Açıklama |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Yok | İşletme ve kullanıcı oluşturur, JWT döner |
| POST | `/api/v1/auth/login` | Yok | JWT döner |
| GET | `/api/v1/tenant` | JWT veya `X-Api-Key` | Geçerli işletmeyi döner |
| POST | `/api/v1/api-keys` | JWT | API anahtarı üretir |
| GET | `/api/v1/api-keys` | JWT | Anahtarları gizli kısımları olmadan listeler |
| DELETE | `/api/v1/api-keys/{id}` | JWT | Anahtarı iptal eder |
| POST | `/api/v1/payments` | JWT veya `X-Api-Key` | Ödeme oluşturur ve provizyon alır |
| GET | `/api/v1/payments` | JWT veya `X-Api-Key` | Ödemeleri listeler (`status`, `limit`, `before`) |
| GET | `/api/v1/payments/{id}` | JWT veya `X-Api-Key` | Ödemeyi işlem geçmişiyle döner |
| POST | `/api/v1/payments/{id}/capture` | JWT veya `X-Api-Key` | Tutarın tamamını veya bir kısmını tahsil eder |
| POST | `/api/v1/payments/{id}/void` | JWT veya `X-Api-Key` | Provizyonu iptal eder |
| POST | `/api/v1/payments/{id}/refunds` | JWT veya `X-Api-Key` | Tam veya kısmi iade yapar |
| POST | `/api/v1/webhook-endpoints` | JWT | Endpoint ekler, imza anahtarını bir kez gösterir |
| GET | `/api/v1/webhook-endpoints` | JWT | Endpoint'leri listeler |
| DELETE | `/api/v1/webhook-endpoints/{id}` | JWT | Devre dışı bırakır, bekleyen teslimatları iptal eder |
| GET | `/api/v1/webhook-endpoints/{id}/deliveries` | JWT | Son teslimatları gösterir |
| POST | `/api/v1/webhook-endpoints/deliveries/{id}/retry` | JWT | Başarısız teslimatı yeniden sıraya alır |
| GET | `/actuator/health` | Yok | Sağlık kontrolü |

Ödeme endpoint'lerindeki tüm `POST` istekleri `Idempotency-Key` başlığı ister. `202` yanıtı, bankadan henüz sonuç alınamadığını gösterir; ödemeyi daha sonra sorgulamak gerekir.

## Ödeme durumları

```text
PENDING ──> AUTHORIZED ──> CAPTURED ──> PARTIALLY_REFUNDED ──> REFUNDED
   │             │             └──────────────────────────────────^
   ├──> DECLINED └──> VOIDED
   └──> FAILED
```

## Webhook'lar

Olaylar: `payment.authorized`, `payment.declined`, `payment.failed`, `payment.captured`, `payment.voided`, `payment.refunded`, `payment.operation_failed`.

Teslimat **en az bir kez** garantilidir ve sıra garantisi yoktur. Alıcı `X-Kasa-Event-Id` ile tekrarları ayıklamalı, sıralama için gövdedeki `createdAt` alanını kullanmalıdır. 2xx dışındaki her yanıt başarısız sayılır; 8 denemeden sonra teslimat `FAILED` olur ve elle yeniden denenebilir.

İmza `X-Kasa-Signature: t=<unix-zaman>,v1=<hex>` biçimindedir. Alıcı, `<t>.<ham gövde>` metninin imza anahtarıyla HMAC-SHA256 değerini hesaplayıp `v1` ile sabit zamanlı karşılaştırmalı, eski `t` değerlerini de reddetmelidir.

Yerelde `localhost` hedefine göndermek için `KASA_WEBHOOKS_ALLOW_INSECURE_TARGETS=true` tanımlanır. Bu ayar canlıda kapalı kalmalıdır.

**Bilinen sınırlar:** İmza anahtarları veritabanında şifrelenmeden tutulur, çünkü imzalarken özgün değer gerekir; gerçek sistemde KMS gerekir. URL kontrolü ile bağlantı arasında DNS yanıtı değişebilir; bunu tamamen kapatmak için çıkış proxy'si gerekir.

## Test kart token'ları

Banka simüle edilir; davranışı token belirler. Böylece her hata senaryosu istenildiği an tekrar üretilebilir.

| Token | Davranış |
|---|---|
| `tok_success` | Her işlem başarılı |
| `tok_declined`, `tok_insufficient_funds` | Provizyon reddedilir |
| `tok_timeout_processed` | Banka işlemi yapar ama yanıt gelmez; kurtarma işi `AUTHORIZED` yapar |
| `tok_timeout_lost` | İstek bankaya ulaşmaz; kurtarma işi `FAILED` yapar |
| `tok_capture_declined` | Provizyon başarılı, capture reddedilir |
| `tok_capture_timeout`, `tok_refund_timeout` | İşlem bankada yapılır ama yanıt gelmez; kurtarma işi tamamlar |

## Yol haritası

- [x] **Defter çekirdeği:** Hesaplar, çift taraflı kayıtlar, idempotency, mutabakat
- [x] **İşletme ve kimlik doğrulama:** Kayıt, JWT, API anahtarları, PostgreSQL Row Level Security
- [x] **Ödeme akışı:** Durum makinesi (authorize, capture, void, refund), simüle banka, hata senaryoları
- [x] **Webhook:** Transactional outbox, `FOR UPDATE SKIP LOCKED`, üstel geri çekilmeli yeniden deneme, HMAC imzası
- [ ] **Hesap kesimi ve mutabakat:** İşletmelere toplu ödeme, banka dosyasıyla karşılaştırma, zamanlanmış işler
- [ ] **Frontend:** Ödeme sayfası ve işletme paneli (React, TypeScript)
- [ ] **Vitrin:** k6 yük testi, Prometheus ve Grafana, GitHub Actions, canlı demo

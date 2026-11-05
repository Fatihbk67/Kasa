/** Shared pieces: errors, statuses, dialogs, formatting helpers, notifications and the language picker. */
export const COMMON: Record<string, string> = {
  // Language and theme
  'Dil': 'Language',
  'Sistem': 'System',
  'Açık': 'Light',
  'Koyu': 'Dark',

  // Roles
  'Sahip': 'Owner',
  'Yönetici': 'Admin',
  'Görüntüleyici': 'Viewer',

  // Errors
  'Girilen bilgiler geçerli değil.': 'The details entered are not valid.',
  'Oturumunuz sona erdi, lütfen yeniden giriş yapın.': 'Your session has ended. Please sign in again.',
  'Bu işlem için yetkiniz yok.': 'You do not have permission to do this.',
  'Kayıt bulunamadı.': 'Not found.',
  'Bu işlem şu anki durumda yapılamıyor.': 'This cannot be done in the current state.',
  'Bu bağlantının süresi doldu.': 'This link has expired.',
  'Girilen tutar kullanılabilir tutarı aşıyor.': 'The amount entered is more than is available.',
  'Çok fazla deneme yapıldı. Lütfen bir dakika sonra yeniden deneyin.': 'Too many attempts. Please try again in a minute.',
  'Sunucuya ulaşılamadı. Bağlantınızı kontrol edip yeniden deneyin.': 'The server could not be reached. Check your connection and try again.',
  'Beklenmeyen bir hata oluştu.': 'Something went wrong.',
  'Oturum geçersiz. Yeniden giriş yapın.': 'The session is no longer valid. Please sign in again.',
  'İşlem tamamlanamadı.': 'The operation could not be completed.',

  // Payment statuses
  'Bankadan yanıt bekleniyor': 'Waiting for the bank',
  'Provizyon alındı': 'Authorized',
  'Reddedildi': 'Declined',
  'Başarısız': 'Failed',
  'İptal edildi': 'Cancelled',
  'Tahsil edildi': 'Captured',
  'Kısmen iade edildi': 'Partially refunded',
  'İade edildi': 'Refunded',

  // Failure codes
  'Kart bankası işlemi reddetti.': 'The card issuer declined the payment.',
  'Kartta yeterli bakiye yok.': 'The card has insufficient funds.',
  'Kart bilgisi geçersiz.': 'The card details are invalid.',
  'Banka tahsilatı reddetti.': 'The bank rejected the capture.',
  'İstek bankaya ulaşmadı, para hareketi olmadı.': 'The request never reached the bank; no money moved.',
  'Alıcı banka hesabı kapalı.': 'The receiving bank account is closed.',
  'Banka bu provizyonu bulamadı.': 'The bank could not find this authorization.',
  'Ödeme bir risk kuralına takıldı ve bankaya gönderilmedi.': 'The payment was stopped by a risk rule and never sent to the bank.',
  'Kart sahibi doğrulaması (3D Secure) tamamlanmadı.': 'Cardholder authentication (3D Secure) was not completed.',
  'Ödeme iyzico sayfasında tamamlanmadı.': 'The payment was not completed on the iyzico page.',

  // Risk
  'Aynı kartla kısa sürede çok fazla deneme': 'Too many attempts with the same card in a short time',
  'Kart bankadan art arda reddedildi': 'The card was declined several times in a row',
  'Tutar belirlenen sınırın üstünde': 'The amount is above the set limit',
  'Kart engelli kartlar listesinde': 'The card is on the blocklist',
  'İncelemede': 'In review',
  'İncelemede reddedildi': 'Rejected in review',
  'İncelendi, onaylandı': 'Reviewed and approved',
  'Risk nedeniyle engellendi': 'Blocked for risk',

  // Other statuses
  'Bekliyor': 'Pending',
  'Başarılı': 'Succeeded',
  'Yolda': 'In transit',
  'Ödendi': 'Paid',
  'Sırada': 'Queued',
  'Teslim edildi': 'Delivered',
  'Yanıt bekliyor': 'Needs response',
  'İnceleniyor': 'Under review',
  'Kazanıldı': 'Won',
  'Kaybedildi': 'Lost',
  'Kontenjan doldu': 'Sold out',
  'Süresi doldu': 'Expired',
  'Kapatıldı': 'Disabled',
  'İlk ödeme bekleniyor': 'Awaiting first payment',
  'Aktif': 'Active',
  'Ödeme gecikti': 'Past due',
  'Ödenmedi': 'Unpaid',
  'Tahsil edilemedi': 'Uncollectible',

  // Shared components
  'Yükleniyor…': 'Loading…',
  'Kopyala': 'Copy',
  'Kopyalandı': 'Copied',
  'Bu değeri şimdi kaydedin. Güvenlik nedeniyle bir daha gösterilmeyecek.': 'Save this value now. For security it will not be shown again.',
  'Vazgeç': 'Cancel',
  'İşleniyor…': 'Processing…',

  // Notifications
  'az önce': 'just now',
  '{count} dk önce': '{count} min ago',
  '{count} sa önce': '{count} h ago',
  '{count} gün önce': '1 day ago|{count} days ago',
  'Bildirimler': 'Notifications',
  'Bildirimler, {count} okunmamış': 'Notifications, {count} unread',
  'Tümünü okundu say': 'Mark all as read',
  'Henüz bildirim yok. İtirazlar, hesap kesimleri ve hesap durumunuz burada görünür.': 'No notifications yet. Disputes, payouts and your account status appear here.',

  // Charts and dashboard helpers
  'Günlere göre tahsilat grafiği': 'Captures by day',
  'Tahsilat: {amount}': 'Captured: {amount}',
  'İade: {amount}': 'Refunded: {amount}',
  '{count} işlem': '1 transaction|{count} transactions',
  'Günaydın': 'Good morning',
  'İyi günler': 'Good afternoon',
  'İyi akşamlar': 'Good evening',
  'İyi geceler': 'Good night',
};

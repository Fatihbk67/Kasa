import { useEffect, useState } from 'react';
import { CopyButton } from './components';
import { t } from './i18n';

/** Groups the key in fours, as authenticator apps show it, so it is easier to type without a slip. */
export function groupKey(secret: string): string {
  return secret.replace(/(.{4})(?=.)/g, '$1 ');
}

/**
 * Adds an account to an authenticator app: a QR code to scan, with the key spelled out for typing by hand.
 * The QR is drawn in the browser, so the secret is never sent to another service to render it.
 */
export function AuthenticatorSetup({ secret, uri }: { secret: string; uri: string }) {
  const [image, setImage] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    // Loaded only here, so the library stays out of every other page.
    import('qrcode')
      .then((qr) => qr.toDataURL(uri, { margin: 1, width: 200, errorCorrectionLevel: 'M' }))
      .then((url) => current && setImage(url))
      .catch(() => current && setImage(null));
    return () => {
      current = false;
    };
  }, [uri]);

  return (
    <div className="authenticator-setup">
      {image && <img className="authenticator-qr" src={image} width={200} height={200} alt={t('Doğrulama uygulaması için QR kod')} />}
      <ol className="authenticator-steps">
        <li>{t('Telefonunuzda Google Authenticator, Microsoft Authenticator veya Authy uygulamasını açın.')}</li>
        <li>{t('+ simgesine dokunun ve QR kodu tarayın.')}</li>
        <li>
          {t('Tarayamıyorsanız "kurulum anahtarı gir" seçeneğine şu anahtarı yazın:')}
          <div className="secret-row">
            <code className="secret">{groupKey(secret)}</code>
            <CopyButton value={secret} />
          </div>
        </li>
      </ol>
    </div>
  );
}

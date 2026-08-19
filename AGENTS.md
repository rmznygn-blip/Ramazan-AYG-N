# Kripto Teknik Analist - Geliştirme Kuralları

Bu dosya uygulamanın temel çalışma prensiplerini, mimarisini ve kullanıcı tercihlerini kalıcı olarak saklar:

## Kullanıcı Tercihleri ve Çalışma Modeli
1. **Sade, Düz ve Kararlı Analist:**
   - Hayalet pencere (floating overlay), erişilebilirlik okuyucusu ve otomatik bot emirleri tamamen kaldırılmıştır.
   - Uygulama sadece gerçek, kararlı, matematiksel ve yapay zekâ destekli bir **Kripto Teknik Analisti & Koçu** olarak çalışır.

2. **Kesinlikle Zararına Satış Yok (Spot Sabır & Sıfır Zarar Kuralı):**
   - Sistemde Stop-Loss (panik zarar satışı) KESİNLİKLE YOKTUR.
   - Spot piyasada likidasyon (patlama) riski olmadığı için, fiyat düşse bile zararına satılmaz; kârlı limit satış emri Midas'ta açık tutularak sabırla beklenir.
   - **Maksimum 3 Aşamalı Kademeli Alım (3-Tier DCA):** Sonsuza kadar ekleme yapılmaz. Bir coin için en fazla 3 kademe (1. İlk Giriş, 2. Dip Destek, 3. Son Savunma) planlanır. 3. kademeden sonra ekleme durdurulur ve sadece kârlı çıkış beklenir.
   - **Kasa Uyumu & Gerçek Nakit Disiplini:** Kademeli alım miktarları sadece ve sadece kasadaki gerçek boş USDT'ye göre bölünür (örn: $100 kasa ➔ $30 / $35 / $35). Olmayan parayla hiçbir işlem planlanmaz.

3. **Midas Kripto & USDT Çekirdeği:**
   - Tüm işlemler, kasalar ve hedefler **USDT** (Tether) üzerinden hesaplanır.
   - Yaşam döngüsü: Bankadan USD yatırma ➔ Midas'ta USDT alma ➔ Kripto al-sat ➔ Çekerken USDT'yi USD'ye dönüştürme.
   - Komisyon: Midas %0.20 alım + %0.20 satım (%0.40 toplam) her çıkış hedefine otomatik dahil edilir (net kâr hedefi).

4. **Veri Kaynağı:**
   - Dünyanın en likit tahtası **Binance Global REST & Kline API** üzerinden 5 dakikalık gerçek mumlar (OHLCV) ve anlık USDT fiyatları.

5. **Kasa & Sermaye Koruyucu:**
   - Minimum Kasa Eşiği (Örn: $50 USDT) altına inildiğinde komisyon/risk optimizasyonu için alım önerisi durdurulur.
   - Akıllı Nakit Akışı Denetçisi: Nakit azaldığında gereksiz soru sormaz, USD çekimi olduğunu kendisi anlar.

6. **Kalıcı Hafıza & Kendini Geliştirme (Room DB):**
   - Yapılan her işlem, kazanma oranı (win rate) ve coin bazlı hafıza kartları kalıcı olarak Room DB'de saklanır.
   - Haftalık AI Raporu tek tıkla kopyalanabilir ve buradaki asistana getirilerek sistem haftadan haftaya geliştirilir.

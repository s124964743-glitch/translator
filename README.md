# 喀啦鍵盤 (Kala Keyboard)

Android 機械軸音效輸入法。永久免費、無廣告、無網路權限。

- 繁體中文注音（大千排列）＋選字列＋聯想字，英文 QWERTY，Gboard 風格佈局
- 9 種真實錄音的機械軸音效（青/茶/紅/黑/奶油/NK Cream/水晶紫/靜電容/IBM），按下與放開各有聲音
- 14 種主題＋自訂背景圖片、LED 流光、震動、按鍵放大提示
- 鍵盤上方工具列可直接切換主題與軸體

資料來源
- 音效：Mechvibes 社群音效包（經 MIT 授權的 SweetTypeTone 收錄）與 Bucklespring（IBM Model M）的錄音，
  由 `tools/build_sounds.py` 切成單鍵樣本
- 注音詞庫：McBopomofo（MIT）資料，由 `tools/build_dict.py` 產生 `assets/zhuyin.txt`

建置：`gradle assembleRelease`，或推到 GitHub 由 Actions 自動建置並發佈 APK。

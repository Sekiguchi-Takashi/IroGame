# いろあてゲーム (IroGame)

3歳向けの知育アプリ。キャラクターごとに違うクイズが遊べます: らっこ=どうぶつクイズ(イラストを見てひらがな3択)、かめ=いろクイズ(色の板をひらがな3択)、ぺんぎん=かずクイズ(板の上のぺんぎん1〜10羽を数えて数字を選ぶ)。正解すると紙吹雪と「せいかい！」の大げさなお祝いアニメーション。

トップ画面は砂浜で、3匹が海の中を泳いだり潜ったりして遊んでいます。タッチすると浜辺に一列に並んでクイズが始まります。左下の「目かくしうさぎ」ボタンでかくれんぼ画面へ: 森と公園を背景にうさぎが隠れていて、耳・手・足など見えている部分をタッチすると「みつけた！」の演出とともに見つかり、また別の場所に隠れます。左下のぺんぎんボタンで砂浜に戻ります。

- 色は8種類: あか・あお・きいろ・みどり・ぴんく・おれんじ・むらさき・みずいろ
- 間違えてもブブーと震えるだけで、何度でもやり直せます
- 効果音つき（端末のメディア音量に連動）

## 初回セットアップ手順 (Termux)

GitHubで新しいリポジトリ `IroGame` を作成してからpushします。
zipは展開すると `IroGame` フォルダが作られるので、ホーム直下に散らばりません。

```bash
cp ~/storage/downloads/IroGame.zip ~/
cd ~
unzip IroGame.zip
cd IroGame
git init
git add .
git status   # .bash_history等が混ざっていないことを確認
git commit -m "initial commit"
git branch -M main
git remote add origin https://<TOKEN>@github.com/Sekiguchi-Takashi/IroGame.git
git push -u origin main
```

## APK取得

GitHubのActionsタブ → 完了したrun → Artifacts「apk」をダウンロード → 解凍して app-debug.apk をインストール。

## カスタマイズ箇所
- アプリ名: app/src/main/res/values/strings.xml
- アイコン: app/src/main/res/drawable/ic_launcher_foreground.xml と values/ic_launcher_background.xml
- 色の追加・変更: GameView.kt 冒頭の colorList
- お祝いの長さ: GameView.kt answer() 内の 3000（ミリ秒）

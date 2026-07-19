# IroGame 引き継ぎドキュメント

このファイルを新しいチャットの冒頭に貼り付ければ、続きから作業できます。

---

## 1. 開発環境（MemoAppと同じパイプライン）

- 端末: Android スマホのみ。Termux で git 操作、コンパイルは **GitHub Actions**
- リポジトリ: `https://github.com/Sekiguchi-Takashi/IroGame`（main ブランチ）
- ローカル作業ディレクトリ: `~/IroGame`
- AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.9（Actions側で固定、**wrapper なし**）
- compileSdk 34 / minSdk 26 / namespace `com.sekiguchi.irogame`
- `app/debug.keystore` をリポジトリにコミット済み（署名固定）
- **外部ライブラリ ゼロ**。UI は XML を使わず全て Canvas への手描き
- 成果物: Actions の Artifacts「apk」→ `app-debug.apk`

### 更新の流れ
1. Claude が `IroGame-vNN.zip`（**毎回バージョン番号付きの別名**）を作る
2. ユーザーがダウンロード（`~/storage/downloads/`）
3. Termux で解凍 → 変更ファイルを `~/IroGame` へコピー
4. `git add . && git commit -m "..." && git push`
5. Actions が緑✓になったら APK をインストール

```bash
rm -rf ~/tmp-iro
unzip -q ~/storage/downloads/IroGame-vNN.zip -d ~/tmp-iro
cp ~/tmp-iro/IroGame/app/src/main/java/com/sekiguchi/irogame/GameView.kt ~/IroGame/app/src/main/java/com/sekiguchi/irogame/
cp ~/tmp-iro/IroGame/app/build.gradle.kts ~/IroGame/app/
cd ~/IroGame
git status   # modified が出ることを必ず確認
git add . && git commit -m "..." && git push
```

---

## 2. 現在のバージョン

**versionCode 8 / versionName 1.7**（フリーズ修正版）

---

## 3. ファイル構成

```
IroGame/
├── .github/workflows/build.yml
├── build.gradle.kts / settings.gradle.kts
├── README.md / HANDOVER.md
└── app/
    ├── build.gradle.kts       ← versionCode をここで上げる
    ├── debug.keystore
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/sekiguchi/irogame/
        │   ├── MainActivity.kt   ← BGM再生のみ（約40行）
        │   └── GameView.kt       ← ゲーム全体（約1200行、単一ファイル）
        └── res/
            ├── values/strings.xml（アプリ名「いろあてゲーム」）
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
            ├── raw/bgm.mp3               ← 「雨音のカフェ」音量0.2でループ
            └── drawable-nodpi/
                ├── bg_park.jpg     公園の背景（元 1024x1536）
                ├── bg_beach.jpg    海辺の背景（元 1024x1024）
                ├── st_dog.png / st_rabbit.png / st_boar.png    （公園の3匹）
                └── st_whale.png / st_turtle.png / st_crab.png  （海辺の3匹）
```

---

## 4. アプリの中身

### 画面1: MAIN（砂浜トップ）
- 空・太陽・流れる雲・波・砂浜を Canvas で描画
- らっこ／かめ／ぺんぎんが海中を泳ぎ、ときどき潜る（半透明の水＋泡）
- どれかをタッチ → 0.55秒かけて浜辺に一列整列（Phase.LINEUP）→ クイズ開始
- 左下に「かくれんぼ」ボタン（目を隠したうさぎ）→ 画面2へ

### クイズ3モード（キャラが板を持ち上げる）
| キャラ | モード | 内容 | 解答欄 |
|---|---|---|---|
| らっこ | ANIMAL | 板に動物イラスト（8種、手描き） | ひらがな3択 |
| かめ | COLOR | 板がランダムな色（8色） | ひらがな3択 |
| ぺんぎん | COUNT | 板の上にぺんぎん1〜10羽 | 数字1〜10（5×2） |

正解 → 紙吹雪110枚＋虹色回転光線＋「せいかい！すごーい！」＋上昇音、★加算、3秒後に復帰。
不正解 → ボタンが震えてブブー音、何度でも選び直せる。

### 画面2: HIDE（公園のかくれんぼ）
- 背景 `bg_park.jpg`。いぬ／うさぎ／いのししの**体の一部だけ**を Canvas で描画
  - いぬ＝巻いたしっぽ、うさぎ＝両耳、いのしし＝鼻
- 隠れ場所5か所（`hideSpots`、元画像1024x1536座標）: すべり台の上／砂場の上／ブランコの上／木馬の上／ベンチの下
- 上部ヒント「だれかの しっぽが みえるよ！」
- 発見 → ポラロイド風写真カード（該当ステッカー画像）＋「いぬさん はっけん！」＋紙吹雪、3.2秒後に別の場所へ
- 左下: ぺんぎんボタン→MAIN、右下…ではなく**右上**: 亀ボタン「うみべへ」→画面3

### 画面3: HIDE2（海辺のかくれんぼ）
- 背景 `bg_beach.jpg`。くじら／かめ／かにの一部を Canvas で描画
  - くじら＝潮吹き（**海の中の2か所限定**）、かめ＝甲羅、かに＝両手のハサミ
- 隠れ場所6か所（`beachSpots`、元画像1024x1024座標、`{キャラ, x, y}`）
- 右上: 木ボタン「こうえんへ」→画面2、左下: ぺんぎんボタン→MAIN

---

## 5. 座標系の仕組み（ズレ調整はここ）

背景画像は中央 crop で全画面表示。元画像座標 → 画面座標の変換は `mx()` / `my()`。

```kotlin
ms   = max(w / bg.width, h / bg.height)   // 拡大率
mdx  = (w - bg.width  * ms) / 2f          // 中央寄せオフセット
mdy  = (h - bg.height * ms) / 2f
imgK = bg.width / 1024f                   // 元画像1024基準への正規化
fun mx(px: Float) = px * imgK * ms + mdx
fun my(py: Float) = py * imgK * ms + mdy
```

隠れ場所がズレたら `hideSpots` / `beachSpots` の数値だけ直す。
**数値を増やす = 右／下に動く。**
実機スクリーンショットをもらって逆算するのが確実。

---

## 6. これまでハマった罠（再発防止）

1. **コード差し替えは全文書き直しが安全**。部分パッチを重ねた結果、`hitTopRight` などが二重定義になりビルドが失敗した（Kotlin はコンパイルエラー）。編集後は重複関数・変数を機械的にチェックすること。
2. **`onDraw` の末尾に `postInvalidateOnAnimation()` が必須**。これが無いと再描画が止まり、起動直後にフリーズしたように見える（実際に発生）。
3. **zip は毎回別名**（`IroGame-v9.zip` など）。同名だとブラウザが `IroGame (1).zip` として保存し、古いファイルを解凍してしまい「nothing to commit」になる。
4. Termux では `cd ~/IroGame` を忘れると `not a git repository` エラー。
5. GitHub の認証は `git remote set-url origin https://<TOKEN>@github.com/Sekiguchi-Takashi/IroGame.git`。トークンは classic、スコープ repo + workflow。
6. リソース名は ASCII 必須（日本語ファイル名の mp3 は `bgm.mp3` に改名済み）。

---

## 7. 次にやりたいこと（未着手のアイデア）

- 隠れ場所の座標の実機微調整（スクリーンショットベース）
- 隠れ場所やキャラクターの追加
- BGM のオン／オフ切り替えボタン
- ★スコアの保存（SharedPreferences。現状はアプリ終了で消える）

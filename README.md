# Smali Quick Viewer (MVP)

Android 向けの最小構成 Smali Viewer です。

## MVP機能
- 起動時にサンプルAPK（アプリ自身のAPK）を自動読込
- APK選択 (`classes*.dex` を読み取り)
- クラス/メソッド検索
- smaliライク表示（命令一覧）
- invoke参照ジャンプ（`# Lpkg/Class;->method` コメントをタップ）
- ブックマーク機能は未実装（要件どおり）

## 使い方
1. Android Studio で開く
2. 実機/エミュレータで起動
3. 起動直後はサンプルAPK（アプリ自身）を表示
4. 必要に応じて `APKを選択` を押して別の APK を選ぶ
5. 左でクラス選択、右で命令を閲覧
6. `invoke-*` 行をタップすると対象クラスにジャンプ

## 注意
- MVPのため、完全なbaksmali出力ではなく、dex命令ベースの軽量表示です。
- 解析対象が大きいAPKの場合、端末性能により時間がかかります。


## Gradle Wrapper について
- このリポジトリではレビュー制約によりバイナリ（`gradle-wrapper.jar`）をコミットしていません。
- 必要に応じてローカルで以下を実行して生成してください。

```bash
gradle wrapper --gradle-version 8.14.3 --distribution-type bin --no-validate-url
```

- 生成後は `./gradlew <task>` で実行できます。

## トラブルシューティング

### `java.lang.OutOfMemoryError: Java heap space` が出る場合
- `gradle.properties` で Gradle/Kotlin デーモンのヒープを増やしてください（本リポジトリでは設定済み）。
- それでも不足する場合は `org.gradle.jvmargs` の `-Xmx` をさらに大きくしてください（例: `-Xmx6g`）。
- Android Studio の **Settings > Build, Execution, Deployment > Compiler** でもビルドメモリ設定を確認してください。

import json
import os
import subprocess

# The list of verified working plugins to keep
keep_names = {
    "DiziPal",
    "DiziPod",
    "FilmModu",
    "FullHDFilmizlesene",
    "HDFilmCehennemi",
    "JetFilmizle",
    "KultFilmler",
    "RecTV",
    "Sinewix",
    "Watch2Movies",
    "WebdramaTurkey2",
    "WebteIzle",
    "CanliTV"
}

# 1. Checkout builds branch
subprocess.run(["git", "checkout", "builds"], check=True)

# 2. Filter plugins.json
with open("plugins.json", "r", encoding="utf-8") as f:
    all_plugins = json.load(f)

kept_plugins = [p for p in all_plugins if p["name"] in keep_names]
print(f"Önceki Eklenti Sayısı: {len(all_plugins)}")
print(f"Kalan Çalışan Eklenti Sayısı: {len(kept_plugins)}")
print("Kalanlar:", [p["name"] for p in kept_plugins])

with open("plugins.json", "w", encoding="utf-8") as f:
    json.dump(kept_plugins, f, indent=4, ensure_ascii=False)

# 3. Update repo.json
repo_data = {
    "name": "Enesim (Sadece Çalışanlar)",
    "description": "Kullanıcı Tarafından Test Edilmiş Kesintisiz Çalışan 13 Türk & Global Film, Dizi ve Canlı TV Eklentisi",
    "manifestVersion": 1,
    "pluginLists": [
        "https://raw.githubusercontent.com/tipciekrem/enesim/builds/plugins.json"
    ]
}

with open("repo.json", "w", encoding="utf-8") as f:
    json.dump(repo_data, f, indent=4, ensure_ascii=False)

# 4. Delete unused .cs3 files on disk
kept_cs3 = {f"{name}.cs3" for name in keep_names}
removed_count = 0
for fname in os.listdir("."):
    if fname.endswith(".cs3") and fname not in kept_cs3:
        try:
            os.remove(fname)
            removed_count += 1
            print(f"Silindi (Çöp): {fname}")
        except Exception as e:
            print(f"Silinemedi: {fname} -> {e}")

print(f"Toplam silinen bozuk eklenti dosyası: {removed_count}")

# 5. Git add, commit, push
subprocess.run(["git", "add", "-A"], check=True)
subprocess.run(["git", "commit", "-m", f"chore: keep only verified working plugins ({len(kept_plugins)} plugins)"], check=True)
subprocess.run(["git", "push", "origin", "builds"], check=True)

print("İşlem başarıyla tamamlandı ve origin/builds'e push edildi!")

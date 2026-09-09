#!/usr/bin/env python3
"""
Builds static N-gram assets for ViKey-Telex suggestions.

Outputs (checked into the APK):
    app/src/main/assets/ime/dict/vi_ngrams.json
    app/src/main/assets/ime/dict/en_ngrams.json
Format: {"bigrams": {"prev|next": count}, "trigrams": {"w1|w2|w3": count}}

Keys are folded-lowercase (NFD strip + d-stroke map + lower), byte-identical
to the keys VietnameseLanguageProvider / EnglishSuggestionProvider build at
runtime, so static and personal counts merge without migration.

Sources of counts here (in priority order):
  1. Curated seed phrases/pairs below (hand-picked high-frequency
     conversational collocations). Phrases explode into both bigram and
     trigram observations; pairs add bigram observations only.
  2. (Future) corpus-derived counts merged on top: drop an OpenSubtitles /
     Wikipedia token stream into EXTRA_COUNTS as {(w1, w2): n} /
     {(w1, w2, w3): n} and re-run. Static counts are plain integers; the
     on-device scorer combines static + personal with stupid backoff.

Counts are plausibility tiers (core 250-400, common 100-200, moderate 40-100),
not measured frequencies. Personal observations (weight x8 at runtime) still
win for the individual user.
"""

import json
import unicodedata
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "ime" / "dict"


def fold(word: str) -> str:
    """Mirrors VietnameseLanguageProvider.foldVietnamese + lowercase."""
    out = []
    for c in unicodedata.normalize("NFD", word):
        if c == "đ":
            out.append("d")
        elif c == "Đ":
            out.append("D")
        elif unicodedata.category(c) == "Mn":
            continue
        else:
            out.append(c)
    return "".join(out).lower()


def valid_token(tok: str) -> bool:
    return bool(tok) and all(c.isalpha() or c == "'" for c in tok)


def build(phrases, pairs):
    bigrams: dict[str, int] = {}
    trigrams: dict[str, int] = {}

    def add_bigram(a, b, n):
        a, b = fold(a), fold(b)
        if valid_token(a) and valid_token(b):
            bigrams[f"{a}|{b}"] = bigrams.get(f"{a}|{b}", 0) + n

    def add_trigram(a, b, c, n):
        a, b, c = fold(a), fold(b), fold(c)
        if valid_token(a) and valid_token(b) and valid_token(c):
            trigrams[f"{a}|{b}|{c}"] = trigrams.get(f"{a}|{b}|{c}", 0) + n

    for phrase, n in phrases:
        toks = phrase.split()
        for i in range(len(toks) - 1):
            add_bigram(toks[i], toks[i + 1], n)
        for i in range(len(toks) - 2):
            add_trigram(toks[i], toks[i + 1], toks[i + 2], n)
    for a, b, n in pairs:
        add_bigram(a, b, n)

    bigrams = dict(sorted(bigrams.items(), key=lambda kv: -kv[1]))
    trigrams = dict(sorted(trigrams.items(), key=lambda kv: -kv[1]))
    return {"bigrams": bigrams, "trigrams": trigrams}


# ---------------------------------------------------------------- Vietnamese
VI_PHRASES = [
    ("tôi không biết", 400), ("bạn có thể", 350), ("cảm ơn bạn", 350),
    ("xin lỗi bạn", 300), ("không có gì", 300), ("tôi muốn đi", 200),
    ("bạn muốn gì", 150), ("đi đâu vậy", 200), ("ở nhà thôi", 120),
    ("khi nào đi", 150), ("tại sao vậy", 180), ("như thế nào", 250),
    ("như vậy là", 150), ("rất nhiều người", 150), ("có phải không", 250),
    ("phải không bạn", 120), ("được không bạn", 200), ("có được không", 200),
    ("làm sao đây", 150), ("để tôi xem", 120), ("cho tôi hỏi", 180),
    ("bạn tên gì", 150), ("tôi tên là", 200), ("rất vui được", 150),
    ("hẹn gặp lại", 200), ("chúc ngủ ngon", 150), ("chúc mừng bạn", 120),
    ("ăn cơm chưa", 200), ("đang làm gì", 200), ("ở đâu vậy", 150),
    ("mấy giờ rồi", 150), ("bao nhiêu tiền", 150), ("nhanh lên nào", 100),
    ("từ từ thôi", 100), ("cẩn thận nhé", 120), ("giữ sức khỏe", 120),
    ("đi cẩn thận", 100), ("về nhà chưa", 120), ("tới nơi chưa", 100),
    ("đợi tôi với", 100), ("chờ chút nhé", 100), ("để sau nhé", 80),
    ("mai gặp nhé", 100), ("hôm nay sao", 100), ("ngày mai đi", 100),
    ("tuần sau nhé", 80), ("giờ này rồi", 80), ("trễ giờ rồi", 80),
    ("kẹt xe quá", 120), ("mưa to quá", 100), ("nắng gắt quá", 80),
    ("mệt quá rồi", 100), ("đói bụng quá", 100), ("buồn ngủ quá", 120),
    ("vui quá trời", 80), ("hay quá vậy", 80), ("mắc quá vậy", 80),
    ("đông quá trời", 80), ("tôi cũng vậy", 200), ("bạn cũng vậy", 120),
    ("không sao đâu", 200), ("không vấn đề gì", 150), ("có chuyện gì", 150),
    ("chuyện gì vậy", 150), ("sao tự nhiên", 80), ("để tôi nghĩ", 100),
    ("tôi nghĩ là", 180), ("theo tôi thì", 120), ("nói thật là", 120),
    ("thật ra thì", 150), ("vấn đề là", 150), ("quan trọng là", 120),
    ("một chút thôi", 120), ("một lát nữa", 120), ("lâu lắm rồi", 120),
    ("mới đây thôi", 100), ("hồi nãy giờ", 80), ("cả ngày nay", 100),
    ("cả tuần nay", 80), ("tháng này bận", 80), ("dạo này sao", 100),
    ("khỏe không bạn", 150), ("gia đình khỏe", 100), ("công việc sao", 120),
    ("học hành sao", 100), ("thi cử sao", 80), ("ăn uống đầy", 80),
    ("ngủ sớm đi", 120), ("đừng thức khuya", 120), ("nhớ giữ gìn", 80),
    ("đi đâu đó", 100), ("làm gì đó", 120), ("ăn gì đó", 100),
    ("uống gì không", 120), ("cần giúp gì", 120), ("giúp tôi với", 150),
    ("làm ơn giúp", 100), ("cảm phiền bạn", 80), ("xin phép về", 80),
    ("tôi về trước", 100), ("bạn về sau", 80), ("đi chung không", 100),
    ("cho quá giang", 80), ("tiện đường không", 80), ("gần đây thôi", 100),
    ("xa quá đi", 80), ("ngay đây nè", 100), ("ở đằng kia", 80),
    ("qua đường cẩn", 60), ("nhìn trước ngó", 60), ("tôi đói quá", 120),
    ("tôi no rồi", 120), ("ăn thêm đi", 100), ("uống thêm đi", 80),
    ("ngon tuyệt vời", 100), ("dở tệ luôn", 60), ("cay quá trời", 80),
    ("mặn quá vậy", 60), ("ngọt quá vậy", 60), ("nóng hổi luôn", 80),
    ("nguội ngắt rồi", 60),
]

VI_PAIRS = [
    ("tôi", "là", 400), ("tôi", "đi", 250), ("tôi", "ăn", 200),
    ("tôi", "muốn", 300), ("tôi", "cần", 200), ("tôi", "thích", 200),
    ("tôi", "ghét", 100), ("tôi", "nhớ", 150), ("tôi", "quên", 100),
    ("tôi", "hiểu", 150), ("tôi", "nghĩ", 200), ("tôi", "tin", 100),
    ("tôi", "thấy", 150), ("tôi", "nghe", 120), ("tôi", "đọc", 100),
    ("tôi", "viết", 100), ("tôi", "làm", 250), ("tôi", "mua", 150),
    ("tôi", "bán", 80), ("tôi", "học", 150), ("tôi", "chơi", 120),
    ("tôi", "ngủ", 150), ("tôi", "dậy", 100), ("tôi", "tắm", 80),
    ("bạn", "là", 200), ("bạn", "đi", 150), ("bạn", "ăn", 120),
    ("bạn", "muốn", 200), ("bạn", "cần", 120), ("bạn", "thích", 150),
    ("bạn", "nghĩ", 120), ("bạn", "làm", 150), ("bạn", "học", 120),
    ("bạn", "có", 250), ("bạn", "không", 150), ("anh", "đi", 150),
    ("anh", "làm", 150), ("em", "đi", 120), ("em", "làm", 120),
    ("con", "đi", 100), ("cháu", "học", 80),
    ("không", "có", 400), ("không", "phải", 350), ("không", "thể", 300),
    ("không", "biết", 350), ("không", "muốn", 200), ("không", "cần", 200),
    ("không", "thích", 150), ("không", "nhớ", 120), ("không", "hiểu", 150),
    ("không", "ngờ", 100), ("không", "sao", 200), ("không", "được", 250),
    ("không", "kịp", 120), ("không", "ổn", 100),
    ("chưa", "có", 200), ("chưa", "xong", 150), ("chưa", "ăn", 150),
    ("chưa", "ngủ", 120), ("chưa", "đi", 150), ("chưa", "làm", 150),
    ("chưa", "biết", 150), ("đã", "xong", 200), ("đã", "ăn", 150),
    ("đã", "đi", 150), ("đã", "làm", 150), ("đã", "biết", 150),
    ("đang", "làm", 200), ("đang", "ăn", 150), ("đang", "đi", 150),
    ("đang", "học", 120), ("đang", "chơi", 100), ("đang", "xem", 120),
    ("đang", "nghe", 100), ("đang", "đọc", 100),
    ("sẽ", "đi", 150), ("sẽ", "làm", 120), ("sẽ", "ăn", 100),
    ("sẽ", "mua", 100), ("vừa", "ăn", 100), ("vừa", "đi", 100),
    ("mới", "đi", 100), ("mới", "làm", 100),
    ("hết", "rồi", 200), ("xong", "rồi", 200), ("xong", "chưa", 150),
    ("được", "rồi", 250), ("được", "chưa", 150), ("được", "không", 200),
    ("có", "thể", 300), ("có", "không", 200), ("có", "chưa", 120),
    ("có", "rồi", 150), ("có", "gì", 150),
    ("phải", "làm", 200), ("phải", "đi", 200), ("phải", "ăn", 120),
    ("phải", "học", 120), ("cần", "mua", 100), ("cần", "làm", 100),
    ("muốn", "đi", 200), ("muốn", "ăn", 150), ("muốn", "mua", 120),
    ("muốn", "xem", 100), ("thích", "ăn", 150), ("thích", "đi", 120),
    ("thích", "xem", 100), ("nhớ", "nhà", 100), ("nhớ", "bạn", 100),
    ("quên", "mất", 100), ("hiểu", "rồi", 150), ("hiểu", "chưa", 100),
    ("nghĩ", "sao", 120), ("làm", "gì", 200), ("làm", "xong", 150),
    ("làm", "ơn", 200), ("mua", "gì", 120), ("học", "gì", 100),
    ("chơi", "gì", 100), ("ngủ", "đi", 150), ("ngủ", "ngon", 150),
    ("thức", "khuya", 80), ("dậy", "sớm", 100),
]

# ----------------------------------------------------------------- English
EN_PHRASES = [
    ("i don't know", 400), ("thank you very", 300), ("how are you", 350),
    ("what are you", 250), ("where are you", 200), ("see you later", 200),
    ("see you soon", 150), ("talk to you", 150), ("good morning", 250),
    ("good night", 200), ("good afternoon", 120), ("good evening", 120),
    ("happy birthday", 150), ("merry christmas", 100), ("have a good", 200),
    ("have a great", 150), ("take care", 200), ("be careful", 120),
    ("no problem", 250), ("of course", 200), ("sounds good", 150),
    ("let's go", 250), ("come on", 200), ("hold on", 150),
    ("let me know", 250), ("let me see", 150), ("give me", 150),
    ("tell me", 150), ("help me", 120), ("call me", 150),
    ("text me", 120), ("what do you", 200), ("what is the", 250),
    ("what time is", 200), ("how do you", 150), ("how much is", 200),
    ("how many people", 150), ("where is the", 200), ("when is the", 150),
    ("why is it", 150), ("who is that", 150), ("this is the", 300),
    ("that is why", 250), ("it is a", 300), ("there is a", 250),
    ("there are many", 200), ("here you go", 120), ("i am going", 350),
    ("you are welcome", 300), ("he is here", 200), ("she is here", 200),
    ("we are going", 200), ("they are coming", 200), ("i was thinking", 200),
    ("it was great", 200), ("i have to", 250), ("you have to", 200),
    ("i need to", 200), ("i want to", 250), ("i like it", 200),
    ("i love it", 200), ("i think so", 250), ("i feel sick", 150),
    ("i know right", 200), ("i mean it", 200), ("i guess so", 150),
    ("i hope so", 150), ("do you want", 300), ("are you sure", 300),
    ("can you please", 250), ("will you come", 200), ("would you like", 150),
    ("could you please", 150), ("did you see", 200), ("have you seen", 200),
    ("as soon as", 200), ("in the morning", 200), ("at the moment", 150),
    ("on the way", 150), ("by the way", 200), ("for a while", 120),
    ("once in a", 100), ("kind of tired", 100), ("sort of busy", 80),
    ("not really sure", 120), ("too good to", 100), ("so far so", 100),
    ("day by day", 80), ("step by step", 100), ("again and again", 80),
]

EN_PAIRS = [
    ("thank", "you", 400), ("good", "morning", 250), ("good", "night", 200),
    ("good", "luck", 120), ("good", "job", 150), ("nice", "to", 150),
    ("nice", "work", 100), ("great", "job", 150), ("well", "done", 150),
    ("no", "problem", 250), ("of", "course", 200), ("sounds", "good", 150),
    ("come", "on", 200), ("hold", "on", 150),
    ("my", "name", 150), ("your", "name", 100), ("phone", "number", 120),
    ("i", "am", 350), ("you", "are", 300), ("he", "is", 200),
    ("she", "is", 200), ("we", "are", 200), ("they", "are", 200),
    ("it", "is", 300), ("there", "is", 250), ("this", "is", 300),
    ("that", "is", 250), ("what", "is", 250), ("where", "is", 200),
    ("i", "have", 250), ("you", "have", 200), ("i", "need", 200),
    ("i", "want", 250), ("i", "like", 200), ("i", "love", 200),
    ("i", "think", 250), ("i", "know", 200), ("i", "mean", 200),
    ("i", "hope", 150), ("do", "you", 300), ("are", "you", 300),
    ("can", "you", 250), ("will", "you", 200), ("would", "you", 150),
    ("could", "you", 150), ("did", "you", 200), ("have", "you", 200),
    ("how", "are", 300), ("how", "much", 200), ("how", "many", 150),
    ("what", "do", 200), ("what", "time", 200), ("when", "is", 150),
    ("why", "is", 150), ("who", "is", 150), ("which", "one", 120),
    ("let", "me", 200), ("give", "me", 150), ("tell", "me", 150),
    ("help", "me", 120), ("call", "me", 150), ("with", "me", 150),
    ("for", "me", 150), ("as", "soon", 200), ("by", "the", 200),
    ("in", "the", 300), ("on", "the", 250), ("at", "the", 200),
    ("to", "the", 200), ("for", "the", 200), ("of", "the", 300),
    ("and", "then", 200), ("but", "still", 120), ("so", "tired", 100),
    ("very", "tired", 120), ("really", "tired", 120), ("pretty", "good", 120),
    ("too", "late", 120), ("too", "far", 100), ("too", "much", 150),
    ("a", "lot", 250), ("lots", "of", 150), ("kind", "of", 150),
    ("best", "friend", 120), ("my", "friend", 150), ("new", "phone", 100),
    ("last", "night", 200), ("last", "week", 150), ("next", "week", 150),
    ("every", "day", 200), ("every", "morning", 120), ("all", "day", 150),
    ("all", "right", 200), ("right", "now", 200), ("right", "here", 150),
    ("over", "there", 120), ("over", "here", 120), ("come", "here", 150),
    ("go", "home", 150), ("stay", "home", 120), ("work", "from", 100),
]


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, phrases, pairs in (("vi", VI_PHRASES, VI_PAIRS), ("en", EN_PHRASES, EN_PAIRS)):
        data = build(phrases, pairs)
        path = OUT_DIR / f"{name}_ngrams.json"
        path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{path.name}: {len(data['bigrams'])} bigrams, {len(data['trigrams'])} trigrams")


if __name__ == "__main__":
    main()

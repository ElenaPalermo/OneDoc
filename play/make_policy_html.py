"""Собирает страницу политики конфиденциальности для GitHub Pages из play/privacy-policy.md.

Источник текста — только Markdown; HTML в docs/ не править вручную, а пересобирать:
    python play/make_policy_html.py
Поддерживается то подмножество Markdown, что есть в политике: заголовки #/##/###, абзацы, списки «- »,
разделитель «---», **жирный**, `код` и ссылки [текст](адрес). Только стандартная библиотека Python.
"""
import html
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, 'play', 'privacy-policy.md')
OUT_DIR = os.path.join(ROOT, 'docs')

# Разделы по языкам: заголовок второго уровня → код языка (id якоря и атрибут lang).
SECTIONS = {'Русский': 'ru', 'English': 'en', 'Italiano': 'it'}
# Якоря из Markdown (как их строит GitHub) → id разделов на странице.
ANCHORS = {'#русский': '#ru', '#english': '#en', '#italiano': '#it'}


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', text)
    text = re.sub(r'`(.+?)`', r'<code>\1</code>', text)
    text = re.sub(r'\[(.+?)\]\((.+?)\)', lambda m: f'<a href="{ANCHORS.get(m.group(2), m.group(2))}">{m.group(1)}</a>', text)
    return text


def convert(md):
    body, paragraph, items = [], [], []
    section_open = False

    def flush():
        if paragraph:
            body.append(f'<p>{inline(" ".join(paragraph))}</p>')
            paragraph.clear()
        if items:
            body.append('<ul>' + ''.join(f'<li>{inline(i)}</li>' for i in items) + '</ul>')
            items.clear()

    for line in md.splitlines():
        stripped = line.strip()
        if not stripped:
            flush()
        elif stripped == '---':
            flush()
        elif stripped.startswith('### '):
            flush()
            body.append(f'<h3>{inline(stripped[4:])}</h3>')
        elif stripped.startswith('## '):
            flush()
            title = stripped[3:]
            lang = SECTIONS.get(title)
            if section_open:
                body.append('</section>')
            body.append(f'<section id="{lang}" lang="{lang}"><h2>{inline(title)}</h2>' if lang else f'<section><h2>{inline(title)}</h2>')
            section_open = True
        elif stripped.startswith('# '):
            flush()
            body.append(f'<h1>{inline(stripped[2:])}</h1>')
        elif stripped.startswith('- '):
            if paragraph:
                flush()
            items.append(stripped[2:])
        else:
            if items:
                flush()
            paragraph.append(stripped)
    flush()
    if section_open:
        body.append('</section>')
    return '\n'.join(body)


STYLE = '''
:root { --bg: #ffffff; --text: #1c1b1f; --muted: #5f5e66; --accent: #1f6fd1; --line: #e3e5ea; --code: #f2f4f7; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #121212; --text: #e3e2e6; --muted: #a3a2aa; --accent: #8ab4f8; --line: #2c2c30; --code: #1f1f23; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text);
       font: 16px/1.6 -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }
main { max-width: 760px; margin: 0 auto; padding: 32px 16px 64px; }
h1 { font-size: 1.6rem; line-height: 1.3; margin: 0 0 12px; }
h2 { font-size: 1.35rem; margin: 48px 0 8px; padding-top: 24px; border-top: 1px solid var(--line); }
h3 { font-size: 1.05rem; margin: 24px 0 4px; }
p, li { margin: 6px 0; }
ul { padding-left: 22px; }
a { color: var(--accent); }
code { background: var(--code); padding: 1px 5px; border-radius: 4px; font-size: .92em; }
nav.langs { margin: 8px 0 0; color: var(--muted); }
'''


def page(title, content, lang='ru'):
    return f'''<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>{STYLE}</style>
</head>
<body>
<main>
{content}
</main>
</body>
</html>
'''


def main():
    md = open(SOURCE, encoding='utf-8').read()
    os.makedirs(OUT_DIR, exist_ok=True)
    content = convert(md)
    # Строка с переключателем языков идёт сразу после заголовка — оформляем как навигацию.
    content = re.sub(r'<p>(<a href="#ru">.*?)</p>', r'<nav class="langs">\1</nav>', content, count=1)
    open(os.path.join(OUT_DIR, 'privacy.html'), 'w', encoding='utf-8', newline='\n').write(
        page('OneDoc — Privacy Policy', content))
    index = ('<h1>OneDoc</h1>\n'
             '<p>Просмотрщик документов для Android · Document viewer for Android · Lettore di documenti per Android</p>\n'
             '<ul><li><a href="privacy.html#ru">Политика конфиденциальности</a></li>'
             '<li><a href="privacy.html#en">Privacy Policy</a></li>'
             '<li><a href="privacy.html#it">Informativa sulla privacy</a></li></ul>')
    open(os.path.join(OUT_DIR, 'index.html'), 'w', encoding='utf-8', newline='\n').write(page('OneDoc', index))
    # Без Jekyll: файлы публикуются как есть.
    open(os.path.join(OUT_DIR, '.nojekyll'), 'w').close()
    print('docs/privacy.html, docs/index.html')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()

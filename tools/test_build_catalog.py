import unittest
import build_catalog as c

class CatalogSelectionTest(unittest.TestCase):
    def test_language_is_audio_not_subtitles(self):
        for title in ['Film 1080p DUB', 'Film ENG + Sub (Rus)', 'Film RUS SUB', 'Film Sub (Rus, Eng)', 'Film 1080p D', 'Film ENG + Subtitles: RUS', 'Film Sub: (Rus, Eng)']:
            self.assertIsNone(c.russian_audio(title, 'other'), title)
        for title in ['Film MVO + Sub (Eng)', 'Film LostFilm', 'Film RUS ENG', 'Фильм 1080p']:
            self.assertIsNotNone(c.russian_audio(title, 'ru' if 'Фильм' in title else 'other'), title)

    def test_title_and_year_do_not_match_remake_or_spinoff(self):
        self.assertTrue(c.title_matches('Король Лев / The Lion King (1994) 1080p', ['Король Лев'], '1994', True))
        self.assertFalse(c.title_matches('Король Лев (2019)', ['Король Лев'], '1994', True))
        self.assertFalse(c.title_matches('Метод Фрейда (2012)', ['Метод'], '', False))
        self.assertFalse(c.title_matches('Чернобыль: Зона отчуждения [S01]', ['Чернобыль'], '', False))
        self.assertTrue(c.title_matches('The.Lion.King.1994.1080p', ['The Lion King'], '1994', True))

    def test_season_before_word_does_not_capture_episode_range(self):
        self.assertEqual(c.season_scope('Дюна (1 сезон: 1-6 серии из 6)'), [{'season':1,'episode':0,'episodeFrom':1,'episodeTo':6}])
        self.assertEqual(c.season_scope('Выживалити (3 сезон: 9 выпуск из 11)'), [{'season':3,'episode':0,'episodeFrom':9,'episodeTo':9}])

    def test_release_notations(self):
        self.assertEqual(c.season_scope('Show [02x01-08]'), [{'season':2,'episode':0,'episodeFrom':1,'episodeTo':8}])
        self.assertEqual(c.season_scope('Show S01E03'), [{'season':1,'episode':3}])
        self.assertEqual([s['season'] for s in c.season_scope('Show Сезоны: 1-3')], [1,2,3])
        self.assertEqual(c.season_scope('Show [01-07]'), [])

    def test_complete_and_partial_multi_season_ranges_are_season_relative(self):
        kitchen = 'Кухня / Сезон: 1-6 / Серии: 1-120 из 120'
        self.assertEqual(c.season_scope(kitchen, dict.fromkeys(range(1, 7), 20)),
                         [dict(season=s, episode=0, episodeFrom=1, episodeTo=20) for s in range(1, 7)])
        partial = 'Шоу (1-3 сезоны: 5-8 серии из 12)'
        self.assertEqual(c.season_scope(partial, {1: 3, 2: 4, 3: 5}),
                         [dict(season=2, episode=0, episodeFrom=2, episodeTo=4),
                          dict(season=3, episode=0, episodeFrom=1, episodeTo=1)])
        for lengths in ({1: 3, 3: 5}, {1: 3, 2: 4, 3: 7}, {}):
            self.assertEqual(c.season_scope(partial, lengths, default_season=1), [])

    def test_mixed_pack_keeps_explicit_seasons_and_separate_ranges(self):
        mixed = 'Шоу / Сезоны: 1,3 / Серии: 1-4 из 4 + Сезон: 4 / Серия: 5'
        self.assertEqual(c.season_scope(mixed, {1: 2, 2: 8, 3: 2, 4: 9}),
                         [dict(season=1, episode=0, episodeFrom=1, episodeTo=2),
                          dict(season=3, episode=0, episodeFrom=1, episodeTo=2),
                          dict(season=4, episode=0, episodeFrom=5, episodeTo=5)])
        self.assertEqual(c.season_lengths([{'season': 1, 'episode': n} for n in (1, 3)]), {})

    def test_separate_episodes_are_not_limited_to_two_per_season(self):
        seed={'name':'Выживалити. Легенда Альфа','origin':'ru','type':'series'}
        rows=[{'title':f'Выживалити. Легенда Альфа [04x{episode:02}] (2026) HDTV 1080p',
               'quality':'1080p','seeds':10,'magnet':'magnet:?xt=urn:btih:'+f'{episode*10+variant:040x}'}
              for episode in range(1,4) for variant in range(3)]
        selected=c.variants(seed,{},rows)
        self.assertEqual(len(selected),6)
        self.assertEqual([r['episodeFrom'] for r in selected],[1,1,2,2,3,3])

    def test_variants_reject_unavailable_unsafe_quality_and_deduplicate(self):
        seed={'name':'Фильм','origin':'other','type':'movie'};meta={'name':'Film','releaseInfo':'2000'}
        base={'title':'Фильм (2000) RUS 1080p','quality':'1080p','seeds':10,'magnet':'magnet:?xt=urn:btih:'+'a'*40}
        rows=[base,dict(base),dict(base,seeds=0,magnet=base['magnet'].replace('a','b')),dict(base,title='Фильм (2000) RUS 3D'),dict(base,title='Фильм (2000) ENG + Sub (Rus)'),dict(base,title='Фильм (2000) MVO Telesync 1080p')]
        self.assertEqual(len(c.variants(seed,meta,rows)),1)

    def test_homonymous_show_rejects_releases_before_its_premiere(self):
        seed={'name':'Титаны','origin':'ru','type':'series','sourceYearMin':2024}
        titles=['Титаны / Titans [S04] (2022) WEBRip 1080p | LostFilm',
                'Титаны [S01] (2024) WEBRip 1080p',
                'Титаны [05x01-06] (2026) WEB-DL 1080p',
                'Титаны [S02] WEBRip 1080p']
        rows=[{'title':title,'quality':'1080p','seeds':10,
               'magnet':'magnet:?xt=urn:btih:'+f'{i:040x}'}
              for i,title in enumerate(titles,1)]
        self.assertEqual([r['title'] for r in c.variants(seed,{},rows)],titles[1:3])

if __name__=='__main__':unittest.main()

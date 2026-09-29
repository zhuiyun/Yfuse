package com.yfuse.core.filesource

/**
 * Two hundred file paths as they sit on real shares — scene releases, Radarr/Sonarr folders,
 * Chinese download sites, fansub groups, bare `第01集` files — each with what a person reading it
 * would say it is: `path | title | year | season | episode`, `-` for unknown.
 *
 * The expectation is the reader's, not the parser's: where the parser gets one wrong, the row
 * stays right and counts as a miss. The last seventy rows, from `Movies/Interstellar (2014)` on,
 * were written after the parser and measured before it was touched again (68 of 70); the two it
 * missed then — a season inside a fansub title bracket, and 最终季 — are fixed now.
 */
internal val MEDIA_NAME_SAMPLES: List<MediaNameSample> =
    """
    Movies/The.Matrix.1999.2160p.UHD.BluRay.x265.10bit.HDR.TrueHD.7.1.Atmos-FGT.mkv | The Matrix | 1999 | - | -
    Movies/Inception (2010)/Inception.2010.1080p.BluRay.x264.DTS-HD.MA.5.1-FGT.mkv | Inception | 2010 | - | -
    Oppenheimer.2023.IMAX.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv | Oppenheimer | 2023 | - | -
    Spider-Man.Across.the.Spider-Verse.2023.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv | Spider-Man Across the Spider-Verse | 2023 | - | -
    Mission.Impossible.Dead.Reckoning.Part.One.2023.2160p.WEB-DL.DDP5.1.Atmos.DV.H.265-FLUX.mkv | Mission Impossible Dead Reckoning Part One | 2023 | - | -
    Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv | Dune Part Two | 2024 | - | -
    Blade.Runner.2049.2017.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-EPSiLON.mkv | Blade Runner 2049 | 2017 | - | -
    1917.2019.2160p.UHD.BluRay.x265-TERMiNAL.mkv | 1917 | 2019 | - | -
    2012 (2009).mkv | 2012 | 2009 | - | -
    Top Gun Maverick (2022) [2160p] [4K] [WEB] [5.1] [YTS.MX].mkv | Top Gun Maverick | 2022 | - | -
    Everything Everywhere All at Once (2022) 1080p BluRay.mkv | Everything Everywhere All at Once | 2022 | - | -
    Parasite.2019.KOREAN.1080p.BluRay.x264.DTS-HD.MA.5.1-FGT.mkv | Parasite | 2019 | - | -
    Amélie.2001.FRENCH.1080p.BluRay.x264.mkv | Amélie | 2001 | - | -
    The.Lord.of.the.Rings.The.Fellowship.of.the.Ring.2001.EXTENDED.2160p.UHD.BluRay.x265.mkv | The Lord of the Rings The Fellowship of the Ring | 2001 | - | -
    Star.Wars.Episode.IV.A.New.Hope.1977.2160p.UHD.BluRay.mkv | Star Wars Episode IV A New Hope | 1977 | - | -
    Harry.Potter.and.the.Philosophers.Stone.2001.2160p.UHD.BluRay.x265.mkv | Harry Potter and the Philosophers Stone | 2001 | - | -
    The.Shawshank.Redemption.1994.REMASTERED.1080p.BluRay.x265.mkv | The Shawshank Redemption | 1994 | - | -
    Movies/Dune (2021) [2160p] [4K] [WEB] [HDR] [5.1] [YTS.MX]/Dune.2021.2160p.HDR.WEB.x265.mkv | Dune | 2021 | - | -
    La.La.Land.2016.1080p.BluRay.x264-SPARKS.mkv | La La Land | 2016 | - | -
    Mad.Max.Fury.Road.2015.2160p.BluRay.REMUX.HEVC.DTS-X.7.1-FGT.mkv | Mad Max Fury Road | 2015 | - | -
    No Country for Old Men 2007 1080p BluRay x264.mkv | No Country for Old Men | 2007 | - | -
    WALL-E.2008.1080p.BluRay.x264.mkv | WALL-E | 2008 | - | -
    Se7en.1995.REMASTERED.1080p.BluRay.x264.mkv | Se7en | 1995 | - | -
    M3GAN.2022.UNRATED.1080p.WEB-DL.DDP5.1.Atmos.H.264.mkv | M3GAN | 2022 | - | -
    Fast.X.2023.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265.mkv | Fast X | 2023 | - | -
    Us.2019.1080p.BluRay.x264.mkv | Us | 2019 | - | -
    It.2017.1080p.BluRay.x264.mkv | It | 2017 | - | -
    Uncut.Gems.2019.1080p.BluRay.x264.mkv | Uncut Gems | 2019 | - | -
    Charlottes.Web.2006.1080p.BluRay.mkv | Charlottes Web | 2006 | - | -
    Mission.Impossible.7.2023.1080p.WEB-DL.mkv | Mission Impossible 7 | 2023 | - | -
    Movies/Oppenheimer (2023) {tmdb-872585}/Oppenheimer (2023).mkv | Oppenheimer | 2023 | - | -
    Movies/Barbie (2023)/movie.mkv | Barbie | 2023 | - | -
    The Grand Budapest Hotel (2014).mkv | The Grand Budapest Hotel | 2014 | - | -
    Léon The Professional 1994 Extended 1080p BluRay.mkv | Léon The Professional | 1994 | - | -
    John.Wick.Chapter.4.2023.2160p.WEB-DL.DDP5.1.Atmos.DV.H.265.mkv | John Wick Chapter 4 | 2023 | - | -
    Guardians.of.the.Galaxy.Vol.3.2023.2160p.WEB-DL.mkv | Guardians of the Galaxy Vol 3 | 2023 | - | -
    Movies/The Thing (1982)/The Thing (1982) Bluray-1080p.mkv | The Thing | 1982 | - | -
    Movies/Alien (1979)/Alien (1979) {imdb-tt0078748} [Bluray-2160p][DV HDR10][TrueHD 7.1 Atmos][x265]-GROUP.mkv | Alien | 1979 | - | -
    Movies/Avatar (2009)/Avatar.2009.UHD.BluRay.2160p.iso | Avatar | 2009 | - | -
    Step.Up.3D.2010.1080p.BluRay.mkv | Step Up 3D | 2010 | - | -
    [电影天堂www.dytt89.com]流浪地球-2019_HD国语中字.mp4 | 流浪地球 | 2019 | - | -
    阳光电影www.ygdy8.com.让子弹飞.BD.720p.国语中字.mkv | 让子弹飞 | - | - | -
    【高清影视之家发布 www.BBQDDQ.com】满江红[60帧率版本][国语音轨+中文字幕].Full.River.Red.2023.60FPS.2160p.WEB-DL.H265.AAC-DreamHD.mkv | 满江红 | 2023 | - | -
    流浪地球2.The.Wandering.Earth.II.2023.2160p.WEB-DL.H265.DDP5.1-OurTV.mkv | 流浪地球2 | 2023 | - | -
    你好，李焕英.Hi.Mom.2021.1080p.WEB-DL.H264.AAC-OurTV.mp4 | 你好，李焕英 | 2021 | - | -
    我不是药神.Dying.to.Survive.2018.1080p.BluRay.x264.mkv | 我不是药神 | 2018 | - | -
    霸王别姬.Farewell.My.Concubine.1993.1080p.BluRay.mkv | 霸王别姬 | 1993 | - | -
    电影/少年的你 (2019)/少年的你.2019.1080p.WEB-DL.mkv | 少年的你 | 2019 | - | -
    唐人街探案3.Detective.Chinatown.3.2021.2160p.WEB-DL.mkv | 唐人街探案3 | 2021 | - | -
    功夫.Kung.Fu.Hustle.2004.1080p.BluRay.mkv | 功夫 | 2004 | - | -
    大话西游之大圣娶亲.A.Chinese.Odyssey.Part.Two.Cinderella.1995.1080p.BluRay.mkv | 大话西游之大圣娶亲 | 1995 | - | -
    无间道.Infernal.Affairs.2002.1080p.BluRay.mkv | 无间道 | 2002 | - | -
    英雄 Hero 2002 1080p BluRay.mkv | 英雄 | 2002 | - | -
    卧虎藏龙.2000.BD1080P.国粤双语.中英双字.mkv | 卧虎藏龙 | 2000 | - | -
    让子弹飞.2010.HD1080P.国语中字.mp4 | 让子弹飞 | 2010 | - | -
    [BD影视分享bd2020.co]八佰.2020.BD1080P.国语中字.mp4 | 八佰 | 2020 | - | -
    千与千寻.Spirited.Away.2001.1080p.BluRay.mkv | 千与千寻 | 2001 | - | -
    你的名字。Your.Name.2016.1080p.BluRay.mkv | 你的名字 | 2016 | - | -
    电影/沙丘2 (2024)/movie.mkv | 沙丘2 | 2024 | - | -
    封神第一部：朝歌风云.2023.2160p.WEB-DL.H265.DDP5.1.mkv | 封神第一部：朝歌风云 | 2023 | - | -
    热辣滚烫.YOLO.2024.2160p.WEB-DL.mkv | 热辣滚烫 | 2024 | - | -
    第二十条.Article.20.2024.2160p.WEB-DL.mkv | 第二十条 | 2024 | - | -
    少林足球.Shaolin.Soccer.2001.1080p.BluRay.国粤双语.mkv | 少林足球 | 2001 | - | -
    阿凡达：水之道.Avatar.The.Way.of.Water.2022.2160p.mkv | 阿凡达：水之道 | 2022 | - | -
    星际穿越 Interstellar (2014) 4K HDR.mkv | 星际穿越 | 2014 | - | -
    [阳光电影www.ygdy8.com].寄生虫.BD.1080p.韩语中字.mkv | 寄生虫 | - | - | -
    年会不能停！.2023.2160p.WEB-DL.mkv | 年会不能停！ | 2023 | - | -
    周处除三害.The.Pig.the.Snake.and.the.Pigeon.2023.1080p.WEB-DL.mkv | 周处除三害 | 2023 | - | -
    电影/【高清】《狂怒》.Fury.2014.1080p.BluRay.mkv | 狂怒 | 2014 | - | -
    Breaking.Bad.S05E14.Ozymandias.1080p.BluRay.x264-ROVERS.mkv | Breaking Bad | - | 5 | 14
    TV/Breaking Bad (2008)/Season 5/Breaking.Bad.S05E14.mkv | Breaking Bad | 2008 | 5 | 14
    TV/Friends (1994)/Season 01/Friends - S01E01 - The One Where Monica Gets a Roommate.mkv | Friends | 1994 | 1 | 1
    The Office (US)/Season 02/The Office (US) - 2x01 - The Dundies.mkv | The Office | - | 2 | 1
    Game.of.Thrones.S08E03.The.Long.Night.1080p.AMZN.WEB-DL.DDP5.1.H.264-GoT.mkv | Game of Thrones | - | 8 | 3
    Stranger Things/Season 4/Stranger.Things.S04E01.Chapter.One.The.Hellfire.Club.2160p.NF.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv | Stranger Things | - | 4 | 1
    The Mandalorian S02E08 Chapter 16 The Rescue 2160p DSNP WEB-DL DDP5 1 Atmos DV HEVC-MZABI.mkv | The Mandalorian | - | 2 | 8
    Doctor.Who.2005.S13E01.1080p.iP.WEB-DL.AAC2.0.H.264.mkv | Doctor Who | 2005 | 13 | 1
    The.Last.of.Us.S01E01.When.Youre.Lost.in.the.Darkness.2160p.HMAX.WEB-DL.DDPA5.1.DV.HEVC-CMRG.mkv | The Last of Us | - | 1 | 1
    Chernobyl/Chernobyl.S01E01.1.23.45.1080p.AMZN.WEB-DL.DDP5.1.H.264.mkv | Chernobyl | - | 1 | 1
    Sherlock/Season 1/Sherlock.S01E01.A.Study.in.Pink.1080p.BluRay.mkv | Sherlock | - | 1 | 1
    The.Crown.S06E10.Sleep.Dearie.Sleep.2160p.NF.WEB-DL.mkv | The Crown | - | 6 | 10
    Shogun.2024.S01E01.Anjin.2160p.DSNP.WEB-DL.mkv | Shogun | 2024 | 1 | 1
    Better Call Saul/Season 6/Better.Call.Saul.S06E13.Saul.Gone.1080p.AMZN.WEB-DL.mkv | Better Call Saul | - | 6 | 13
    The Simpsons/Season 34/The Simpsons - S34E01 - Habeas Tortoise.mkv | The Simpsons | - | 34 | 1
    Dark/Season 1/Dark.S01E01.Secrets.1080p.NF.WEB-DL.mkv | Dark | - | 1 | 1
    Arcane.S02E01.Heavy.Is.the.Crown.1080p.NF.WEB-DL.mkv | Arcane | - | 2 | 1
    Seinfeld/Season 9/Seinfeld - 9x23 - The Finale.mkv | Seinfeld | - | 9 | 23
    TV/Lost/Season 1/Lost.S01E01.Pilot.Part.1.720p.BluRay.mkv | Lost | - | 1 | 1
    Mr.Robot.S01E01.eps1.0_hellofriend.mov.1080p.BluRay.mkv | Mr Robot | - | 1 | 1
    Cowboy Bebop/Season 1/Cowboy Bebop - S01E01 - Asteroid Blues.mkv | Cowboy Bebop | - | 1 | 1
    TV/Planet Earth II (2016)/Season 1/01 - Islands.mkv | Planet Earth II | 2016 | 1 | 1
    TV/Fleabag/Season 2/Fleabag.S02.1080p.AMZN.WEB-DL/Fleabag.S02E03.1080p.AMZN.WEB-DL.mkv | Fleabag | - | 2 | 3
    狂飙/第01集.mp4 | 狂飙 | - | - | 1
    狂飙/狂飙 第01集.mp4 | 狂飙 | - | - | 1
    狂飙/狂飙.EP01.HD1080P.X264.AAC.Mandarin.CHS.mp4 | 狂飙 | - | - | 1
    狂飙/01.mp4 | 狂飙 | - | - | 1
    三体 (2023)/Season 1/三体.S01E03.2023.2160p.WEB-DL.H265.AAC-HHWEB.mkv | 三体 | 2023 | 1 | 3
    繁花.Blossoms.Shanghai.S01E05.2023.2160p.WEB-DL.H265.DDP5.1.mkv | 繁花 | 2023 | 1 | 5
    漫长的季节/漫长的季节 第3集.mp4 | 漫长的季节 | - | - | 3
    庆余年 第二季/庆余年第二季_第12集.mp4 | 庆余年 | - | 2 | 12
    长相思/长相思 第二季 第05集 4K.mp4 | 长相思 | - | 2 | 5
    甄嬛传/甄嬛传.E01.mp4 | 甄嬛传 | - | - | 1
    电视剧/琅琊榜/琅琊榜.第1集.1080p.mp4 | 琅琊榜 | - | - | 1
    电视剧/人世间 (2022)/人世间.S01E10.2022.2160p.WEB-DL.mkv | 人世间 | 2022 | 1 | 10
    隐秘的角落/隐秘的角落.The.Bad.Kids.EP01.2020.1080p.WEB-DL.mkv | 隐秘的角落 | 2020 | - | 1
    山海情/山海情 01.mp4 | 山海情 | - | - | 1
    大江大河/第一季/大江大河 第01集.mp4 | 大江大河 | - | 1 | 1
    大江大河/第二季/大江大河2 第01集.mp4 | 大江大河2 | - | 2 | 1
    白夜追凶/Season 1/白夜追凶.E01.1080p.WEB-DL.mp4 | 白夜追凶 | - | 1 | 1
    庆余年/庆余年.Joy.of.Life.S02E01.2024.2160p.WEB-DL.mkv | 庆余年 | 2024 | 2 | 1
    请回答1988/请回答1988.E01.1080p.mkv | 请回答1988 | - | - | 1
    请回答1988/Reply.1988.S01E01.1080p.mkv | 请回答1988 | - | 1 | 1
    非自然死亡/[人人影视]非自然死亡.EP01.中日双语.720p.mkv | 非自然死亡 | - | - | 1
    凡人修仙传/凡人修仙传 第100集 4K.mp4 | 凡人修仙传 | - | - | 100
    [Nekomoe kissaten][Sousou no Frieren][05][1080p][CHS].mp4 | Sousou no Frieren | - | - | 5
    [Lilith-Raws] Kusuriya no Hitorigoto - 03 [Baha][WEB-DL][1080p][AVC AAC][CHT][MP4].mp4 | Kusuriya no Hitorigoto | - | - | 3
    葬送的芙莉莲/[桜都字幕组] 葬送的芙莉莲 第05话 [1080P][简繁内封].mkv | 葬送的芙莉莲 | - | - | 5
    [ANi] 葬送的芙莉蓮 - 05 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4 | 葬送的芙莉蓮 | - | - | 5
    【喵萌奶茶屋】★10月新番★[葬送的芙莉莲 / Sousou no Frieren][05][1080p][简日双语][招募翻译].mp4 | 葬送的芙莉莲 | - | - | 5
    [VCB-Studio] Sousou no Frieren [Ma10p_1080p]/[VCB-Studio] Sousou no Frieren [05][Ma10p_1080p][x265_flac].mkv | Sousou no Frieren | - | - | 5
    [DBD-Raws][葬送的芙莉莲][05][1080P][BDRip][HEVC-10bit][FLAC].mkv | 葬送的芙莉莲 | - | - | 5
    进击的巨人/Season 4/[Snow-Raws] 进击的巨人 The Final Season 第01话 (BD 1920x1080 HEVC-YUV420P10 FLAC).mkv | 进击的巨人 | - | 4 | 1
    鬼灭之刃/[BeanSub&FZSD][Kimetsu_no_Yaiba][01][GB][1080P][x264_AAC].mp4 | Kimetsu no Yaiba | - | - | 1
    One Piece/[Skymoon-Raws] One Piece 海贼王 - 1085 [ViuTV][WEB-DL][CHT][1080p][AVC AAC].mkv | One Piece | - | - | 1085
    名侦探柯南/[SBSUB][CONAN][1100][1080P][AVC_AAC][CHS_JP](DC5E6A9F).mp4 | 名侦探柯南 | - | - | 1100
    间谍过家家/[桜都字幕组] SPY×FAMILY 第二季 第01话 [1080P][简体内嵌].mp4 | SPY×FAMILY | - | 2 | 1
    孤独摇滚/[Nekomoe kissaten&LoliHouse] Bocchi the Rock! - 01 [WebRip 1080p HEVC-10bit AAC ASSx2].mkv | Bocchi the Rock! | - | - | 1
    完美世界/[GM-Team][国漫][完美世界][Perfect World][2021][151][AVC][GB][1080P].mp4 | 完美世界 | 2021 | - | 151
    [GM-Team][国漫][吞噬星空][Swallowed Star][2020][120][AVC][GB][1080P].mp4 | 吞噬星空 | 2020 | - | 120
    Anime/Spirited Away (2001)/Spirited.Away.2001.1080p.BluRay.x264.mkv | Spirited Away | 2001 | - | -
    Movies/Interstellar (2014)/Interstellar (2014) Remux-2160p.mkv | Interstellar | 2014 | - | -
    Movies/The Prestige (2006)/The.Prestige.2006.1080p.BluRay.DTS.x264-ESiR.mkv | The Prestige | 2006 | - | -
    Pirates.of.the.Caribbean.The.Curse.of.the.Black.Pearl.2003.1080p.BluRay.x264.mkv | Pirates of the Caribbean The Curse of the Black Pearl | 2003 | - | -
    The.Hunger.Games.Mockingjay.Part.2.2015.1080p.BluRay.mkv | The Hunger Games Mockingjay Part 2 | 2015 | - | -
    Toy Story 4 (2019) 1080p.mkv | Toy Story 4 | 2019 | - | -
    Kill.Bill.Vol.1.2003.1080p.BluRay.x264.mkv | Kill Bill Vol 1 | 2003 | - | -
    Ocean's.Eleven.2001.1080p.BluRay.mkv | Ocean's Eleven | 2001 | - | -
    E.T.the.Extra-Terrestrial.1982.1080p.BluRay.mkv | E.T. the Extra-Terrestrial | 1982 | - | -
    The.Wolf.of.Wall.Street.2013.1080p.BluRay.x264.mkv | The Wolf of Wall Street | 2013 | - | -
    Inglourious.Basterds.2009.1080p.BluRay.x264.mkv | Inglourious Basterds | 2009 | - | -
    Django.Unchained.2012.1080p.BluRay.x264.mkv | Django Unchained | 2012 | - | -
    The.Revenant.2015.2160p.UHD.BluRay.mkv | The Revenant | 2015 | - | -
    Dunkirk.2017.IMAX.2160p.UHD.BluRay.mkv | Dunkirk | 2017 | - | -
    Gravity.2013.3D.1080p.BluRay.Half-SBS.mkv | Gravity | 2013 | - | -
    Life.of.Pi.2012.1080p.BluRay.mkv | Life of Pi | 2012 | - | -
    Whiplash.2014.1080p.BluRay.x264.mkv | Whiplash | 2014 | - | -
    Get.Out.2017.1080p.BluRay.x264.mkv | Get Out | 2017 | - | -
    Moonlight.2016.1080p.BluRay.mkv | Moonlight | 2016 | - | -
    The.Shape.of.Water.2017.1080p.BluRay.mkv | The Shape of Water | 2017 | - | -
    Green.Book.2018.1080p.BluRay.mkv | Green Book | 2018 | - | -
    CODA.2021.1080p.ATVP.WEB-DL.mkv | CODA | 2021 | - | -
    Nomadland.2020.1080p.WEB-DL.mkv | Nomadland | 2020 | - | -
    The.Irishman.2019.1080p.NF.WEB-DL.mkv | The Irishman | 2019 | - | -
    Roma.2018.1080p.NF.WEB-DL.mkv | Roma | 2018 | - | -
    Drive.My.Car.2021.JAPANESE.1080p.BluRay.mkv | Drive My Car | 2021 | - | -
    Shoplifters.2018.JAPANESE.1080p.BluRay.mkv | Shoplifters | 2018 | - | -
    Oldboy.2003.KOREAN.1080p.BluRay.mkv | Oldboy | 2003 | - | -
    The.Handmaiden.2016.KOREAN.1080p.BluRay.mkv | The Handmaiden | 2016 | - | -
    Train.to.Busan.2016.KOREAN.1080p.BluRay.mkv | Train to Busan | 2016 | - | -
    Movies/Godzilla Minus One (2023)/Godzilla.Minus.One.2023.JAPANESE.1080p.WEB-DL.mkv | Godzilla Minus One | 2023 | - | -
    扫毒2天地对决.2019.HD1080P.国粤双语.中字.mp4 | 扫毒2天地对决 | 2019 | - | -
    战狼2.Wolf.Warrior.2.2017.1080p.BluRay.mkv | 战狼2 | 2017 | - | -
    红海行动.Operation.Red.Sea.2018.1080p.BluRay.mkv | 红海行动 | 2018 | - | -
    流浪地球 (2019)/流浪地球.The.Wandering.Earth.2019.2160p.WEB-DL.mkv | 流浪地球 | 2019 | - | -
    【4K修复版】霸王别姬.1993.2160p.WEB-DL.mkv | 霸王别姬 | 1993 | - | -
    悬崖之上.Cliff.Walkers.2021.2160p.WEB-DL.H265.mkv | 悬崖之上 | 2021 | - | -
    人生大事.2022.1080p.WEB-DL.H264.AAC.mp4 | 人生大事 | 2022 | - | -
    这个杀手不太冷静.2022.HD1080P.国语中字.mp4 | 这个杀手不太冷静 | 2022 | - | -
    奇迹·笨小孩.2022.2160p.WEB-DL.mkv | 奇迹·笨小孩 | 2022 | - | -
    hello!树先生.2011.1080p.WEB-DL.mkv | Hello!树先生 | 2011 | - | -
    寻梦环游记.Coco.2017.1080p.BluRay.国英双语.mkv | 寻梦环游记 | 2017 | - | -
    蜘蛛侠：纵横宇宙.Spider-Man.Across.the.Spider-Verse.2023.2160p.mkv | 蜘蛛侠：纵横宇宙 | 2023 | - | -
    变形金刚：超能勇士崛起.2023.2160p.WEB-DL.mkv | 变形金刚：超能勇士崛起 | 2023 | - | -
    电影/周星驰/功夫 (2004)/功夫.2004.1080p.BluRay.mkv | 功夫 | 2004 | - | -
    [BT天堂btbtt12.com]大红灯笼高高挂.1991.1080p.BluRay.mkv | 大红灯笼高高挂 | 1991 | - | -
    The Walking Dead/Season 11/The.Walking.Dead.S11E24.Rest.in.Peace.1080p.AMZN.WEB-DL.mkv | The Walking Dead | - | 11 | 24
    Grey's Anatomy/Season 20/Grey's.Anatomy.S20E01.1080p.WEB.h264.mkv | Grey's Anatomy | - | 20 | 1
    Brooklyn.Nine-Nine.S08E10.1080p.NF.WEB-DL.mkv | Brooklyn Nine-Nine | - | 8 | 10
    Its.Always.Sunny.in.Philadelphia.S16E01.1080p.WEB.h264.mkv | Its Always Sunny in Philadelphia | - | 16 | 1
    The.Big.Bang.Theory.S12E24.720p.HDTV.x264.mkv | The Big Bang Theory | - | 12 | 24
    How I Met Your Mother/Season 9/How I Met Your Mother - S09E23-E24 - Last Forever.mkv | How I Met Your Mother | - | 9 | 23
    Rick and Morty/Season 7/Rick.and.Morty.S07E01.1080p.WEB.H264.mkv | Rick and Morty | - | 7 | 1
    The.Walking.Dead.Daryl.Dixon.S01E01.1080p.AMZN.WEB-DL.mkv | The Walking Dead Daryl Dixon | - | 1 | 1
    Andor.S01E01.Kassa.2160p.DSNP.WEB-DL.mkv | Andor | - | 1 | 1
    Slow.Horses.S04E01.1080p.ATVP.WEB-DL.mkv | Slow Horses | - | 4 | 1
    漫长的季节/Season 1/漫长的季节 S01E07.mp4 | 漫长的季节 | - | 1 | 7
    繁城之下/繁城之下 第6集 4K 60帧.mp4 | 繁城之下 | - | - | 6
    异人之下/[异人之下][第08集][4K].mp4 | 异人之下 | - | - | 8
    In the Name of the People/人民的名义.In.the.Name.of.People.E01.1080p.mkv | 人民的名义 | - | - | 1
    West Wing/Season 1/The West Wing - 1x01 - Pilot.mkv | The West Wing | - | 1 | 1
    [Nekomoe kissaten][Kaguya-sama wa Kokurasetai - Ultra Romantic][01][1080p][CHS].mp4 | Kaguya-sama wa Kokurasetai - Ultra Romantic | - | - | 1
    [SweetSub&LoliHouse] Kimi no Na wa [BDRip 1080p HEVC-10bit FLAC].mkv | Kimi no Na wa | - | - | -
    [Airota][Yuru Camp Season 2][01][BDRip 1080p AVC AAC][CHS].mp4 | Yuru Camp | - | 2 | 1
    [Haruhana] Bocchi the Rock! - 12 [WebRip 1080p HEVC-10bit AAC][CHS].mkv | Bocchi the Rock! | - | - | 12
    Attack on Titan/Season 3/Attack.on.Titan.S03E12.1080p.BluRay.mkv | Attack on Titan | - | 3 | 12
    [LoliHouse] Kimetsu no Yaiba - Hashira Geiko-hen - 01 [WebRip 1080p HEVC-10bit AAC SRTx2].mkv | Kimetsu no Yaiba - Hashira Geiko-hen | - | - | 1
    [Sakurato] Spy x Family Season 2 [01][AVC-8bit 1080p AAC][CHS].mp4 | Spy x Family | - | 2 | 1
    海贼王/[海贼王][第1086集][1080P].mp4 | 海贼王 | - | - | 1086
    进击的巨人 最终季/进击的巨人 最终季 第16集.mp4 | 进击的巨人 | - | - | 16
    Demon Slayer/Season 1/Demon.Slayer.Kimetsu.no.Yaiba.S01E19.1080p.WEB.mkv | Demon Slayer Kimetsu no Yaiba | - | 1 | 19
    """.trimIndent()
        .lines()
        .filter(String::isNotBlank)
        .map { line ->
            val fields = line.split(" | ").map(String::trim)
            MediaNameSample(
                path = fields[0].split('/'),
                title = fields[1],
                year = fields[2].toIntOrNull(),
                season = fields[3].toIntOrNull(),
                episode = fields[4].toIntOrNull(),
            )
        }

internal data class MediaNameSample(
    val path: List<String>,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
)

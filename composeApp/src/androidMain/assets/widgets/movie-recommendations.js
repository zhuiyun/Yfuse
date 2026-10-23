// Recovered from the user-supplied CapyPlayer 1.1.7 (117020) APK.
// APK SHA-256: 601a2cf31b7b61bd3485b4fcb3ee9e32b42b4947208e86904b6f56fb218fc569
// Yfuse adaptation: manifest, host authentication, validated media identity and pagination.
// See docs/BUNDLED_WIDGETS.md for exact provenance and changes.
var WidgetMetadata = {
  "id": "plugin_builtin_movie_recommend",
  "title": "影视推荐",
  "version": "1.0.0-yfuse.1",
  "author": "CapyPlayer · Yfuse 适配",
  "description": "豆瓣、IMDb、番剧动画与流媒体趋势；内容来自在线推荐服务。",
  "website": "https://recommend.feifeiduck.com",
  "globalParams": [
    {
      "name": "language",
      "label": "内容语言",
      "type": "enumeration",
      "defaultValue": "zh-CN",
      "enumOptions": [
        {
          "title": "简体中文",
          "value": "zh-CN"
        },
        {
          "title": "繁体中文",
          "value": "zh-TW"
        },
        {
          "title": "English",
          "value": "en-US"
        }
      ]
    },
    {
      "name": "page",
      "type": "page",
      "defaultValue": 1
    },
    {
      "name": "limit",
      "label": "每页数量",
      "type": "enumeration",
      "defaultValue": 30,
      "enumOptions": [
        {
          "title": "20 部",
          "value": 20
        },
        {
          "title": "30 部",
          "value": 30
        },
        {
          "title": "50 部",
          "value": 50
        },
        {
          "title": "100 部",
          "value": 100
        }
      ]
    }
  ],
  "modules": [
    {
      "id": "recMovie",
      "title": "电影推荐",
      "functionName": "recMovie",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "chart",
          "label": "榜单",
          "type": "enumeration",
          "defaultValue": "douban/hot-movie",
          "enumOptions": [
            {
              "title": "豆瓣热门电影",
              "value": "douban/hot-movie"
            },
            {
              "title": "豆瓣 Top 250",
              "value": "douban/top250"
            },
            {
              "title": "IMDb 热门电影",
              "value": "imdb/popular-movies"
            },
            {
              "title": "IMDb Top 250 电影",
              "value": "imdb/top250-movies"
            },
            {
              "title": "电影趋势",
              "value": "feed/film-trending"
            },
            {
              "title": "Trakt 最新 4K",
              "value": "trakt/latest-4k"
            }
          ]
        }
      ]
    },
    {
      "id": "recTv",
      "title": "剧集推荐",
      "functionName": "recTv",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "chart",
          "label": "榜单",
          "type": "enumeration",
          "defaultValue": "douban/hot-tv",
          "enumOptions": [
            {
              "title": "豆瓣热门剧集",
              "value": "douban/hot-tv"
            },
            {
              "title": "豆瓣全球高分剧集",
              "value": "douban/global-best-tv"
            },
            {
              "title": "豆瓣热门综艺",
              "value": "douban/hot-variety"
            },
            {
              "title": "IMDb 热门剧集",
              "value": "imdb/popular-tv"
            },
            {
              "title": "IMDb Top 250 剧集",
              "value": "imdb/top250-tv"
            },
            {
              "title": "热门华语剧集",
              "value": "feed/series-cn-popular"
            },
            {
              "title": "热门韩剧",
              "value": "feed/series-kr-popular"
            },
            {
              "title": "剧集趋势",
              "value": "feed/series-trending"
            },
            {
              "title": "烂番茄热门剧集",
              "value": "rottentomatoes/popular-tv"
            }
          ]
        }
      ]
    },
    {
      "id": "recAnime",
      "title": "动画推荐",
      "functionName": "recAnime",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "chart",
          "label": "榜单",
          "type": "enumeration",
          "defaultValue": "douban/hot-animation",
          "enumOptions": [
            {
              "title": "豆瓣热门动画",
              "value": "douban/hot-animation"
            },
            {
              "title": "热门国创动画",
              "value": "feed/anime-cn-popular"
            },
            {
              "title": "Bangumi 高分动画",
              "value": "bangumi/top-anime"
            },
            {
              "title": "MyAnimeList 高分动画",
              "value": "mal/top-anime"
            },
            {
              "title": "MyAnimeList 热门动画",
              "value": "mal/most-popular"
            },
            {
              "title": "即将播出动画",
              "value": "feed/anime-upcoming"
            }
          ]
        }
      ]
    },
    {
      "id": "recCalendar",
      "title": "番剧日历",
      "functionName": "recCalendar",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "chart",
          "label": "榜单",
          "type": "enumeration",
          "defaultValue": "bangumi/calendar",
          "enumOptions": [
            {
              "title": "Bangumi 放送日历",
              "value": "bangumi/calendar"
            },
            {
              "title": "动画放送日历",
              "value": "feed/anime-calendar"
            },
            {
              "title": "巴哈姆特每周动画",
              "value": "bahamut/weekdays"
            }
          ]
        },
        {
          "name": "day",
          "label": "星期",
          "type": "enumeration",
          "defaultValue": "",
          "enumOptions": [
            {
              "title": "全部",
              "value": ""
            },
            {
              "title": "周一",
              "value": "1"
            },
            {
              "title": "周二",
              "value": "2"
            },
            {
              "title": "周三",
              "value": "3"
            },
            {
              "title": "周四",
              "value": "4"
            },
            {
              "title": "周五",
              "value": "5"
            },
            {
              "title": "周六",
              "value": "6"
            },
            {
              "title": "周日",
              "value": "7"
            }
          ]
        }
      ]
    },
    {
      "id": "recTrending",
      "title": "流媒体趋势",
      "functionName": "recTrending",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "chart",
          "label": "榜单",
          "type": "enumeration",
          "defaultValue": "justwatch/streaming-charts",
          "enumOptions": [
            {
              "title": "JustWatch 流媒体榜",
              "value": "justwatch/streaming-charts"
            },
            {
              "title": "电影趋势",
              "value": "feed/film-trending"
            },
            {
              "title": "剧集趋势",
              "value": "feed/series-trending"
            },
            {
              "title": "烂番茄热门剧集",
              "value": "rottentomatoes/popular-tv"
            },
            {
              "title": "Trakt 最新 4K",
              "value": "trakt/latest-4k"
            }
          ]
        }
      ]
    }
  ]
};

var ALLOWED_CHARTS = ["bahamut/weekdays", "bangumi/calendar", "bangumi/top-anime", "douban/global-best-tv", "douban/hot-animation", "douban/hot-movie", "douban/hot-tv", "douban/hot-variety", "douban/top250", "feed/anime-calendar", "feed/anime-cn-popular", "feed/anime-upcoming", "feed/film-trending", "feed/series-cn-popular", "feed/series-kr-popular", "feed/series-trending", "imdb/popular-movies", "imdb/popular-tv", "imdb/top250-movies", "imdb/top250-tv", "justwatch/streaming-charts", "mal/most-popular", "mal/top-anime", "rottentomatoes/popular-tv", "trakt/latest-4k"];

var WORKER_BASE = 'https://recommend.feifeiduck.com';

function cloneParams(source) {
  var result = {};
  if (!source || typeof source !== 'object') {
    return result;
  }
  for (var key in source) {
    if (Object.prototype.hasOwnProperty.call(source, key)) {
      result[key] = source[key];
    }
  }
  return result;
}

function genreTitleWith(genreIds) {
  if (!genreIds || !genreIds.length) {
    return '';
  }

  var genreDict = [
    { id: 10759, name: '动作冒险' },
    { id: 16, name: '动画' },
    { id: 35, name: '喜剧' },
    { id: 80, name: '犯罪' },
    { id: 99, name: '纪录' },
    { id: 18, name: '剧情' },
    { id: 10751, name: '家庭' },
    { id: 10762, name: '儿童' },
    { id: 9648, name: '悬疑' },
    { id: 10763, name: '新闻' },
    { id: 10764, name: '真人秀' },
    { id: 10765, name: 'Sci-Fi & Fantasy' },
    { id: 10766, name: '肥皂剧' },
    { id: 10767, name: '脱口秀' },
    { id: 10768, name: 'War & Politics' },
    { id: 37, name: '西部' },
    { id: 28, name: '动作' },
    { id: 12, name: '冒险' },
    { id: 14, name: '奇幻' },
    { id: 36, name: '历史' },
    { id: 27, name: '恐怖' },
    { id: 10402, name: '音乐' },
    { id: 10749, name: '爱情' },
    { id: 878, name: '科幻' },
    { id: 10770, name: '电视电影' },
    { id: 53, name: '惊悚' },
    { id: 10752, name: '战争' }
  ];

  var picked = genreIds;
  if (picked.length > 2) {
    picked = picked.slice(0, 2);
  }

  var result = [];
  for (var i = 0; i < picked.length; i++) {
    var genreId = picked[i];
    var genreName = null;
    for (var j = 0; j < genreDict.length; j++) {
      if (genreDict[j].id == genreId) {
        genreName = genreDict[j].name;
        break;
      }
    }
    if (genreName) {
      result.push(genreName);
    }
  }

  return result.join(', ');
}

// Worker 条目（TMDB 补全后的 SnapshotItem 风格）-> 与 TMDB 组件一致的标准对象。
function mapItem(item) {
  if (!item) {
    return null;
  }
  var mediaType = item.media_type;
  if (mediaType !== 'movie' && mediaType !== 'tv') return null;
  var id = item.tmdbId != null ? String(item.tmdbId) : '';
  if (!/^[1-9][0-9]*$/.test(id) || !(item.title || item.name)) {
    return null;
  }
  var releaseDate = item.release_date || item.first_air_date || null;

  return {
    id: mediaType + '.' + id,
    type: 'tmdb',
    title: item.title || item.name || '',
    description: item.overview || '',
    releaseDate: releaseDate,
    year: releaseDate ? String(releaseDate).slice(0, 4) : null,
    thumbPath: item.thumb || null,
    backdropPath: item.backdrop_path || null,
    posterPath: item.poster_path || null,
    rating: item.vote_average != null
      ? Math.round(item.vote_average * 10) / 10
      : null,
    mediaType: mediaType,
    genreTitle: genreTitleWith(item.genre_ids),
    link: 'https://www.themoviedb.org/' + mediaType + '/' + id,
    tmdbId: id
  };
}

function unwrapResponse(response) {
  if (!response) {
    throw new Error('推荐服务请求失败：空响应');
  }
  if (response.ok === false) {
    throw new Error('推荐服务请求失败：HTTP ' + String(response.status || 0));
  }

  var payload = response;
  if (response.data !== undefined) {
    payload = response.data;
  }
  if (typeof payload === 'string') {
    try {
      payload = JSON.parse(payload);
    } catch (_) {
      throw new Error('推荐服务返回解析失败');
    }
  }
  if (!payload || typeof payload !== 'object') {
    throw new Error('推荐服务返回无效数据');
  }
  return payload;
}

async function fetchChart(params) {
  var input = cloneParams(params);
  var chart = String(input.chart || '').replace(/^\/+/, '');
  if (ALLOWED_CHARTS.indexOf(chart) < 0) throw new Error('请选择有效榜单');
  delete input.chart;

  var page = Number(input.page || 1);
  var limit = Number(input.limit || 30);
  if (!isFinite(page) || page < 1 || !isFinite(limit) || limit < 1 || limit > 100) throw new Error('页码或每页数量无效');
  page = Math.floor(page);
  limit = Math.floor(limit);
  if ((page - 1) * limit >= 100) return [];
  if (input.day && !/^[1-7]$/.test(String(input.day))) throw new Error('请选择有效星期');
  var requestParams = {};
  requestParams.limit = 100;
  requestParams.language = input.language || 'zh-CN';
  if (input.day !== undefined && input.day !== null && input.day !== '') {
    requestParams.day = String(input.day);
  }

  var base = String(WORKER_BASE || '').replace(/\/+$/, '');
  var response = await Widget.http.get(base + '/' + chart, {
    params: requestParams
  });

  var payload = unwrapResponse(response);
  var start = (page - 1) * limit;
  if (!Array.isArray(payload.items)) throw new Error('推荐服务返回无效列表');
  var results = payload.items.slice(0, 100);
  var seen = {};

  var mapped = [];
  for (var i = 0; i < results.length; i++) {
    var item = mapItem(results[i]);
    if (item && !seen[item.id]) {
      seen[item.id] = true;
      mapped.push(item);
    }
  }
  return mapped.slice(start, start + limit);
}

async function recMovie(params) {
  return await fetchChart(params);
}

async function recTv(params) {
  return await fetchChart(params);
}

async function recAnime(params) {
  return await fetchChart(params);
}

async function recCalendar(params) {
  return await fetchChart(params);
}

async function recTrending(params) {
  return await fetchChart(params);
}

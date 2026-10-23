// Recovered from the user-supplied CapyPlayer 1.1.7 (117020) APK.
// APK SHA-256: 601a2cf31b7b61bd3485b4fcb3ee9e32b42b4947208e86904b6f56fb218fc569
// Yfuse adaptation: manifest, host authentication, validated media identity and pagination.
// See docs/BUNDLED_WIDGETS.md for exact provenance and changes.
var WidgetMetadata = {
  "id": "plugin_builtin_tmdb",
  "title": "TMDB",
  "version": "1.0.0-yfuse.2",
  "author": "CapyPlayer · Yfuse 适配",
  "description": "热映、趋势、热门、高分与分类发现，支持电视网和制作公司筛选。",
  "website": "https://www.themoviedb.org",
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
    }
  ],
  "modules": [
    {
      "id": "nowPlaying",
      "title": "正在热映 / 热播",
      "functionName": "nowPlaying",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "type",
          "label": "影视类型",
          "type": "enumeration",
          "defaultValue": "movie",
          "enumOptions": [
            {
              "title": "电影",
              "value": "movie"
            },
            {
              "title": "剧集",
              "value": "tv"
            }
          ]
        },
        {
          "name": "region",
          "label": "上映地区",
          "type": "input",
          "defaultValue": "CN",
          "required": false
        }
      ]
    },
    {
      "id": "trending",
      "title": "今日 / 本周趋势",
      "functionName": "trending",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "time_window",
          "label": "趋势周期",
          "type": "enumeration",
          "defaultValue": "day",
          "enumOptions": [
            {
              "title": "今日",
              "value": "day"
            },
            {
              "title": "本周",
              "value": "week"
            }
          ]
        }
      ]
    },
    {
      "id": "popular",
      "title": "热门影视",
      "functionName": "popular",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "type",
          "label": "影视类型",
          "type": "enumeration",
          "defaultValue": "movie",
          "enumOptions": [
            {
              "title": "电影",
              "value": "movie"
            },
            {
              "title": "剧集",
              "value": "tv"
            }
          ]
        }
      ]
    },
    {
      "id": "topRated",
      "title": "高分影视",
      "functionName": "topRated",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "type",
          "label": "影视类型",
          "type": "enumeration",
          "defaultValue": "movie",
          "enumOptions": [
            {
              "title": "电影",
              "value": "movie"
            },
            {
              "title": "剧集",
              "value": "tv"
            }
          ]
        }
      ]
    },
    {
      "id": "categories",
      "title": "按类型发现",
      "functionName": "categories",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "type",
          "label": "影视类型",
          "type": "enumeration",
          "defaultValue": "movie",
          "enumOptions": [
            {
              "title": "电影",
              "value": "movie"
            },
            {
              "title": "剧集",
              "value": "tv"
            }
          ]
        },
        {
          "name": "with_genres",
          "label": "类型",
          "type": "enumeration",
          "defaultValue": "10751",
          "enumOptions": [
            {
              "title": "家庭",
              "value": "10751"
            },
            {
              "title": "动作",
              "value": "28"
            },
            {
              "title": "冒险",
              "value": "12"
            },
            {
              "title": "动画",
              "value": "16"
            },
            {
              "title": "喜剧",
              "value": "35"
            },
            {
              "title": "犯罪",
              "value": "80"
            },
            {
              "title": "纪录",
              "value": "99"
            },
            {
              "title": "剧情",
              "value": "18"
            },
            {
              "title": "奇幻",
              "value": "14"
            },
            {
              "title": "历史",
              "value": "36"
            },
            {
              "title": "恐怖",
              "value": "27"
            },
            {
              "title": "音乐",
              "value": "10402"
            },
            {
              "title": "悬疑",
              "value": "9648"
            },
            {
              "title": "爱情",
              "value": "10749"
            },
            {
              "title": "科幻",
              "value": "878"
            },
            {
              "title": "电视电影",
              "value": "10770"
            },
            {
              "title": "惊悚",
              "value": "53"
            },
            {
              "title": "战争",
              "value": "10752"
            },
            {
              "title": "西部",
              "value": "37"
            },
            {
              "title": "动作冒险剧",
              "value": "10759"
            },
            {
              "title": "儿童",
              "value": "10762"
            },
            {
              "title": "新闻",
              "value": "10763"
            },
            {
              "title": "真人秀",
              "value": "10764"
            },
            {
              "title": "科幻奇幻剧",
              "value": "10765"
            },
            {
              "title": "肥皂剧",
              "value": "10766"
            },
            {
              "title": "脱口秀",
              "value": "10767"
            },
            {
              "title": "战争政治剧",
              "value": "10768"
            }
          ]
        }
      ]
    },
    {
      "id": "networks",
      "title": "按电视网发现",
      "functionName": "networks",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "with_networks",
          "label": "电视网 ID",
          "type": "input",
          "defaultValue": "",
          "required": true
        }
      ]
    },
    {
      "id": "companies",
      "title": "按制作公司发现",
      "functionName": "companies",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "with_companies",
          "label": "制作公司 ID",
          "type": "input",
          "defaultValue": "",
          "required": true
        }
      ]
    },
    {
      "id": "getTrendingMovies",
      "title": "电影趋势",
      "functionName": "getTrendingMovies",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "time_window",
          "label": "趋势周期",
          "type": "enumeration",
          "defaultValue": "day",
          "enumOptions": [
            {
              "title": "今日",
              "value": "day"
            },
            {
              "title": "本周",
              "value": "week"
            }
          ]
        }
      ]
    },
    {
      "id": "getTrendingTv",
      "title": "剧集趋势",
      "functionName": "getTrendingTv",
      "cacheDuration": 900,
      "timeoutSeconds": 30,
      "params": [
        {
          "name": "time_window",
          "label": "趋势周期",
          "type": "enumeration",
          "defaultValue": "day",
          "enumOptions": [
            {
              "title": "今日",
              "value": "day"
            },
            {
              "title": "本周",
              "value": "week"
            }
          ]
        }
      ]
    }
  ]
};

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

function normalizeParams(params, defaultLanguage) {
  var input = cloneParams(params);
  var normalized = {};

  for (var key in input) {
    if (!Object.prototype.hasOwnProperty.call(input, key)) {
      continue;
    }
    var value = input[key];
    if (value === null || value === undefined || value === '') {
      continue;
    }
    normalized[key] = value;
  }

  if (!normalized.language && defaultLanguage) {
    normalized.language = defaultLanguage;
  }

  return normalized;
}

function inferMediaType(item, forceMediaType) {
  if (!item || (item.media_type && item.media_type !== 'movie' && item.media_type !== 'tv')) return '';
  if (forceMediaType) {
    return forceMediaType;
  }

  var mediaType = item && item.media_type;
  if (mediaType === 'movie' || mediaType === 'tv') {
    return mediaType;
  }

  if (item && item.title) {
    return 'movie';
  }
  return item && item.name ? 'tv' : '';
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

function mapItem(item, forceMediaType) {
  var mediaType = inferMediaType(item, forceMediaType);
  var id = item && item.id != null ? String(item.id) : '';
  var releaseDate = item && (item.release_date || item.first_air_date)
    ? (item.release_date || item.first_air_date)
    : null;

  return {
    id: mediaType + '.' + id,
    type: 'tmdb',
    title: item ? (item.title || item.name || '') : '',
    description: item ? (item.overview || '') : '',
    releaseDate: releaseDate,
    year: releaseDate ? String(releaseDate).slice(0, 4) : null,
    thumbPath: item ? item.thumb : null,
    backdropPath: item ? item.backdrop_path : null,
    posterPath: item ? item.poster_path : null,
    rating: item && item.vote_average != null
      ? Math.round(item.vote_average * 10) / 10
      : null,
    mediaType: mediaType,
    genreTitle: genreTitleWith(item ? item.genre_ids : null),
    link: 'https://www.themoviedb.org/' + mediaType + '/' + id,
    tmdbId: id
  };
}

function unwrapTmdbResponse(response) {
  if (!response) {
    throw new Error('TMDB 请求失败：空响应');
  }

  if (response.ok === false) {
    throw new Error('TMDB 请求失败：HTTP ' + String(response.status || 0));
  }

  var payload = response;
  if (response.data !== undefined) {
    payload = response.data;
  }

  if (typeof payload === 'string') {
    try {
      payload = JSON.parse(payload);
    } catch (_) {
      throw new Error('TMDB 返回解析失败');
    }
  }

  if (!payload || typeof payload !== 'object') {
    throw new Error('TMDB 返回无效数据');
  }

  return payload;
}

function resolveTimeWindow(params) {
  return params && params.time_window === 'week' ? 'week' : 'day';
}

function includesGenre(item, genreId) {
  var genres = item && item.genre_ids;
  return !!(genres && genres.indexOf(genreId) >= 0);
}

function hasOriginCountry(item, countryCode) {
  var countries = item && item.origin_country;
  return !!(countries && countries.indexOf(countryCode) >= 0);
}

async function fetchData(api, params, forceMediaType, itemFilter) {
  var requestParams = normalizeParams(params, 'zh-CN');
  // Never forward account credentials to the APK's public catalog service.
  for (var key in requestParams) {
    if (key.indexOf('__') === 0 || /^(api_key|apiKey|token|authorization|access_token|request_token|session_id|guest_session_id|cookie)$/i.test(key)) delete requestParams[key];
  }
  var page = Number(requestParams.page || 1);
  if (!isFinite(page) || page < 1 || page > 500) throw new Error('页码需为 1–500');
  requestParams.page = Math.floor(page);
  var normalizedPath = '/' + String(api || '').replace(/^\/+/, '');
  var response;
  try {
    response = unwrapTmdbResponse(await Widget.http.get(
      'https://recommend.feifeiduck.com/tmdb' + normalizedPath,
      { params: requestParams, timeout: 10000 }
    ));
    if (response.success === false || !Array.isArray(response.results)) throw new Error('预置线路返回无效列表');
  } catch (_) {
    // The fallback keeps authentication inside Yfuse, within the module's 30-second budget.
    try {
      response = await Widget.tmdb.get(normalizedPath, { params: requestParams, timeout: 18000 });
    } catch (_) {
      throw new Error('TMDB 线路暂时不可用，请稍后重试');
    }
  }

  var payload = unwrapTmdbResponse(response);
  if (payload.success === false || !Array.isArray(payload.results)) throw new Error('TMDB 返回无效列表');
  var results = payload.results.filter(function(item) {
    return item && /^[1-9][0-9]*$/.test(String(item.id)) &&
      inferMediaType(item, forceMediaType) && (item.title || item.name);
  });

  if (!forceMediaType) {
    var filtered = [];
    for (var i = 0; i < results.length; i++) {
      var mediaType = inferMediaType(results[i], null);
      if (mediaType === 'movie' || mediaType === 'tv') {
        filtered.push(results[i]);
      }
    }
    results = filtered;
  }

  if (typeof itemFilter === 'function') {
    var customFiltered = [];
    for (var k = 0; k < results.length; k++) {
      if (itemFilter(results[k])) {
        customFiltered.push(results[k]);
      }
    }
    results = customFiltered;
  }

  var mapped = [];
  for (var j = 0; j < results.length; j++) {
    mapped.push(mapItem(results[j], forceMediaType));
  }
  return mapped;
}

async function nowPlaying(params) {
  var input = cloneParams(params);
  var type = input.type === 'tv' ? 'tv' : 'movie';
  delete input.type;
  var api = type === 'tv' ? 'tv/on_the_air' : 'movie/now_playing';
  return await fetchData(api, input, type);
}

async function trending(params) {
  var input = cloneParams(params);
  var timeWindow = input.time_window === 'week' ? 'week' : 'day';
  delete input.time_window;
  return await fetchData('trending/all/' + timeWindow, input);
}

async function popular(params) {
  var input = cloneParams(params);
  var type = input.type === 'tv' ? 'tv' : 'movie';
  delete input.type;
  return await fetchData(type + '/popular', input, type);
}

async function topRated(params) {
  var input = cloneParams(params);
  var type = input.type === 'tv' ? 'tv' : 'movie';
  delete input.type;
  return await fetchData(type + '/top_rated', input, type);
}

async function discoverByGenre(params) {
  var input = cloneParams(params);
  var genreId = String(input.with_genres || '10751');
  var type = input.type === 'tv' ? 'tv' : 'movie';

  var onlyMovieGenreIds = [
    '12',
    '14',
    '27',
    '28',
    '36',
    '53',
    '10402',
    '10749',
    '10752'
  ];
  var onlyTvGenreIds = ['10759', '10762', '10763', '10764', '10765', '10766', '10767', '10768'];

  if (genreId === '878' && type === 'tv') {
    genreId = '10765';
  }
  if (onlyMovieGenreIds.indexOf(genreId) >= 0) {
    type = 'movie';
  }
  if (onlyTvGenreIds.indexOf(genreId) >= 0) {
    type = 'tv';
  }

  input.with_genres = genreId;
  delete input.type;

  return await fetchData('discover/' + type, input, type);
}

async function categories(params) {
  return await discoverByGenre(params);
}

async function networks(params) {
  var input = cloneParams(params);
  delete input.type;
  return await fetchData('discover/tv', input, 'tv');
}

async function companies(params) {
  var input = cloneParams(params);
  delete input.type;
  return await fetchData('discover/movie', input, 'movie');
}

// 兼容旧内置数据源方法
async function getTrendingTv(params) {
  var input = normalizeParams(params, 'zh-CN');
  var timeWindow = resolveTimeWindow(input);
  delete input.time_window;
  return await fetchData(
    'trending/tv/' + timeWindow,
    input,
    'tv'
  );
}

async function getTrendingMovies(params) {
  var input = normalizeParams(params, 'zh-CN');
  var timeWindow = resolveTimeWindow(input);
  delete input.time_window;
  return await fetchData(
    'trending/movie/' + timeWindow,
    input,
    'movie'
  );
}

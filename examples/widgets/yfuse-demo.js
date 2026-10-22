var WidgetMetadata = {
  id:'yfuse.demo',title:'Yfuse 卡片示例',version:'1.0.0',author:'Yfuse',description:'离线示例内容，用于体验首页编辑和 36 种展示样式。',
  modules:[{id:'movies',title:'我的精选',functionName:'getMovies',params:[{name:'kind',label:'内容类型',type:'enum',defaultValue:'all',enumOptions:[{title:'全部',value:'all'},{title:'电影',value:'movie'},{title:'剧集',value:'tv'}]}]}]
};
async function getMovies(params){
  var names=['暮色航线','山海之间','星际来信','雨后小镇','漫长夏日','远方的光'];
  return names.map(function(title,i){return {id:'demo-'+i,title:title,year:'2026',rating:8+i/10,description:'示例内容，用于展示卡片布局。点击可查看信息或搜索媒体库。',mediaType:i%2?'movie':'tv',date:'2026-10-'+(21+i),group:i%2?'电影':'剧集',progress:(i+1)/8,seasonNumber:1,episodeNumber:i+1};}).filter(function(x){return !params.kind||params.kind==='all'||x.mediaType===params.kind;});
}

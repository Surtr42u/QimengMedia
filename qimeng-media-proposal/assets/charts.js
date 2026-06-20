// assets/charts.js — ECharts 图表初始化
(function() {
  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();
  var bg = style.getPropertyValue('--bg').trim();

  // --- 10 维评分雷达图 ---
  // 设计权重（详见 GUIDE_ALGORITHM.md）
  // 10 维：tagRelevance/tagCollection/engagement/recency/likeScore/discovery/freshness/browseDepth/randomFactor/dailyPenalty
  var radarChart = echarts.init(document.getElementById('chart-radar'), null, { renderer: 'svg' });
  radarChart.setOption({
    animation: false,
    backgroundColor: 'transparent',
    radar: {
      center: ['50%', '52%'],
      radius: '65%',
      indicator: [
        { name: '标签相关性\n0.22', max: 100 },
        { name: '标签合集\n0.15', max: 100 },
        { name: '互动热度\n0.10', max: 100 },
        { name: '时效\n0.15', max: 100 },
        { name: '发现\n0.20', max: 100 },
        { name: '新鲜度\n0.05', max: 100 },
        { name: '点赞\n0.05', max: 100 },
        { name: '浏览深度\n0.03', max: 100 },
        { name: '随机扰动\n0~0.30', max: 100 },
        { name: '每日惩罚\n-0.80', max: 100 }
      ],
      axisName: {
        color: muted,
        fontSize: 10,
        padding: [2, 2]
      },
      splitArea: {
        areaStyle: {
          color: ['transparent', 'transparent']
        }
      },
      splitLine: {
        lineStyle: { color: rule }
      },
      axisLine: {
        lineStyle: { color: rule }
      }
    },
    series: [{
      type: 'radar',
      name: '设计权重',
      data: [{
        value: [22, 15, 10, 15, 20, 5, 5, 3, 30, 80],
        name: '权重/惩罚值',
        areaStyle: {
          color: accent + '44'
        },
        lineStyle: {
          color: accent,
          width: 2
        },
        itemStyle: {
          color: accent
        },
        symbol: 'circle',
        symbolSize: 6
      }],
      emphasis: {
        disabled: true
      }
    }],
    tooltip: {
      trigger: 'item',
      appendToBody: true,
      backgroundColor: bg2,
      borderColor: rule,
      textStyle: { color: ink, fontSize: 12 }
    }
  });

  window.addEventListener('resize', function() { radarChart.resize(); });
})();
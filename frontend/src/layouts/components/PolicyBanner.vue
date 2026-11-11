<script setup>
import AppIcon from '../../components/AppIcon.vue'
</script>

<template>
  <header class="policy-banner">
    <div class="banner-symbol">
      <AppIcon name="document" :size="34" />
      <span><AppIcon name="check" :size="17" /></span>
    </div>
    <div class="banner-copy">
      <h1>差异化政策管理</h1>
      <p>支持零售与百亿两种视角，规范提交政策信息，快速完成审核</p>
    </div>
    <svg class="banner-art" viewBox="0 0 310 132" fill="none" aria-hidden="true">
      <ellipse
        cx="191"
        cy="82"
        rx="107"
        ry="47"
        fill="#dbe7ff"
        opacity=".65"
        transform="rotate(-14 191 82)"
      />
      <ellipse
        cx="191"
        cy="77"
        rx="123"
        ry="49"
        stroke="#c5d9ff"
        stroke-dasharray="6 9"
        transform="rotate(-14 191 77)"
      />
      <circle cx="267" cy="38" r="10" fill="#bcd2ff" />
      <circle cx="94" cy="65" r="6" fill="#c0d5ff" />
      <g transform="translate(142 14) rotate(12 54 58)">
        <rect x="4" y="8" width="86" height="109" rx="12" fill="#9bbcff" />
        <rect width="82" height="108" rx="10" fill="#eff5ff" stroke="#79a5ff" stroke-width="3" />
        <rect x="22" y="-4" width="38" height="14" rx="6" fill="#87a8dc" />
        <rect x="30" y="-1" width="22" height="4" rx="2" fill="#cfddf2" />
        <path
          d="M19 34h43M19 48h32M19 63h38M19 78h23"
          stroke="#b5cbf5"
          stroke-width="6"
          stroke-linecap="round"
        />
      </g>
      <circle cx="228" cy="100" r="23" fill="#2d79ff" />
      <path
        d="m217 100 7 7 15-16"
        stroke="white"
        stroke-width="4"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path d="m119 23 3 7 7 3-7 3-3 7-3-7-7-3 7-3 3-7Z" fill="#9bbdff" />
    </svg>
  </header>
</template>

<style scoped>
.policy-banner {
  height: 116px;
  overflow: hidden;
  position: relative;
  border: 1px solid #e1eaff;
  border-radius: 9px;
  background: linear-gradient(105deg, #e1ecff 0%, #f0f5ff 56%, #e3edff 100%);
  display: flex;
  align-items: center;
  padding: 0 28px;
  margin-bottom: 18px;
}
.banner-symbol {
  position: relative;
  width: 58px;
  height: 65px;
  border-radius: 15px;
  display: grid;
  place-items: center;
  color: white;
  background: linear-gradient(145deg, #9bc2ff, #4f8ef9);
  box-shadow:
    0 0 0 9px #ffffff40,
    0 9px 18px #4f85d324;
  margin-right: 26px;
  transform: rotate(-3deg);
  flex-shrink: 0;
}
.banner-symbol span {
  position: absolute;
  right: -6px;
  bottom: -2px;
  background: #26c0a0;
  border: 3px solid #e9f3ff;
  border-radius: 7px 7px 10px 10px;
  padding: 4px;
  display: flex;
}
.banner-copy {
  position: relative;
  z-index: 1;
}
.banner-copy h1 {
  color: #1d304d;
  font-size: 22px;
  letter-spacing: 0.02em;
  font-weight: 700;
  margin-bottom: 12px;
}
.banner-copy p {
  font-size: 12px;
  color: #6a7f9f;
  letter-spacing: 0.02em;
}
.banner-art {
  position: absolute;
  height: 136px;
  width: 310px;
  right: 15px;
  bottom: -6px;
}
@media (max-width: 800px) {
  .banner-art {
    display: none;
  }
  .banner-copy h1 {
    font-size: 19px;
  }
  .banner-copy p {
    line-height: 1.8;
  }
}
@media (max-width: 520px) {
  .policy-banner {
    padding: 16px;
    height: auto;
    min-height: 100px;
  }
  .banner-symbol {
    display: none;
  }
}
</style>

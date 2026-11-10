<script setup>
import { ref } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import { usePolicyForm } from '../../../composables/usePolicyForm'
import PolicyDetailsTable from './PolicyDetailsTable.vue'

const fileInput = ref(null)
const {
  form,
  activePlatform,
  files,
  notice,
  platforms,
  selectedPlatforms,
  activePlatformName,
  rows,
  switchChannel,
  togglePlatform,
  selectAll,
  addModel,
  addFiles,
  reset,
  submit,
} = usePolicyForm()

function handleChannelChange(event) {
  if (!switchChannel(event.target.value)) {
    event.target.value = form.value.channel
  }
}

function handleFiles(list) {
  addFiles(list)
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}
</script>

<template>
  <form class="policy-form" @submit.prevent="submit">
    <section class="form-section basic-section" aria-labelledby="basic-title">
      <h2 id="basic-title">
        <span></span>
        一、基础信息
      </h2>
      <div class="basic-grid" :class="{ 'billion-grid': form.channel === 'BILLION' }">
        <label class="field">
          <span>
            1. 渠道
            <b>*</b>
          </span>
          <select :value="form.channel" @change="handleChannelChange">
            <option value="RETAIL">零售渠道</option>
            <option value="BILLION">百亿渠道</option>
          </select>
        </label>
        <div class="field platforms-field">
          <span>
            2. 平台
            <b>*</b>
          </span>
          <div class="platform-options">
            <button
              v-for="[code, name] in platforms"
              :key="code"
              type="button"
              class="platform-chip"
              :class="{ selected: form.platforms.includes(code) }"
              :aria-pressed="form.platforms.includes(code)"
              @click="togglePlatform(code)"
            >
              {{ name }}
            </button>
            <button type="button" class="text-button select-all" @click="selectAll">
              全部选择
            </button>
          </div>
        </div>
        <fieldset v-if="form.channel === 'RETAIL'" class="field">
          <legend>
            3. 核销类型
            <b>*</b>
          </legend>
          <div class="radio-group">
            <label>
              <input v-model="form.verifyType" type="radio" value="SALES" />
              销售维度
            </label>
            <label>
              <input v-model="form.verifyType" type="radio" value="ACTIVATION" />
              激活维度
            </label>
          </div>
        </fieldset>
        <fieldset v-if="form.channel === 'RETAIL'" class="field">
          <legend>
            4. 涉及场景
            <b>*</b>
          </legend>
          <div class="radio-group">
            <label>
              <input v-model="form.scene" type="radio" value="NATIONAL_SUB" />
              国补
            </label>
            <label>
              <input v-model="form.scene" type="radio" value="LOCAL_SUB" />
              地补
            </label>
            <label>
              <input v-model="form.scene" type="radio" value="SELF_SUB" />
              自补
            </label>
          </div>
        </fieldset>
        <fieldset v-if="form.channel === 'RETAIL'" class="field">
          <legend>
            5. 是否为预估政策
            <b>*</b>
          </legend>
          <div class="radio-group">
            <label>
              <input v-model="form.estimated" type="radio" :value="true" />
              是
            </label>
            <label>
              <input v-model="form.estimated" type="radio" :value="false" />
              否
            </label>
          </div>
        </fieldset>
      </div>
    </section>
    <section class="form-section" aria-labelledby="time-title">
      <h2 id="time-title">
        <span></span>
        二、政策时间与详情
      </h2>
      <div class="two-columns time-columns">
        <div class="field">
          <span>
            1. 开始结束时间
            <b>*</b>
          </span>
          <div class="date-range">
            <input
              v-model="form.startTime"
              type="datetime-local"
              step="1"
              aria-label="政策开始时间"
            />
            <span>至</span>
            <input
              v-model="form.endTime"
              type="datetime-local"
              step="1"
              aria-label="政策结束时间"
            />
          </div>
        </div>
        <label class="field">
          <span>2. 特别备注</span>
          <span class="textarea-wrap">
            <textarea
              v-model="form.remark"
              rows="2"
              maxlength="200"
              placeholder="请输入特别备注（选填）"
            ></textarea>
            <small>{{ form.remark.length }} / 200</small>
          </span>
        </label>
      </div>
    </section>
    <section class="form-section model-section" aria-labelledby="models-title">
      <div class="section-heading">
        <h2 id="models-title">
          <span></span>
          三、机型及补贴信息
          <em>（每个机型对应具体的补贴方式）</em>
        </h2>
        <button type="button" class="primary small" @click="addModel">
          <AppIcon name="plus" :size="15" />
          添加机型
        </button>
      </div>
      <div v-if="selectedPlatforms.length" class="platform-detail-nav">
        <span>当前平台</span>
        <button
          v-for="[code, name] in selectedPlatforms"
          :key="code"
          type="button"
          :class="{ active: code === activePlatform }"
          :aria-pressed="code === activePlatform"
          @click="activePlatform = code"
        >
          {{ name }}
          <small>{{ form.details[code]?.length || 0 }}</small>
        </button>
        <span class="platform-hint">各平台分别填写机型、数量与补贴</span>
      </div>
      <PolicyDetailsTable
        v-model="rows"
        :platform-name="activePlatformName"
        :has-platform="Boolean(activePlatform)"
      />
    </section>
    <section class="form-section other-section" aria-labelledby="other-title">
      <h2 id="other-title">
        <span></span>
        四、其他信息
      </h2>
      <div class="two-columns">
        <div class="field">
          <span>1. 上传文件或链接</span>
          <input
            ref="fileInput"
            class="visually-hidden"
            type="file"
            multiple
            @change="handleFiles($event.target.files)"
          />
          <button
            type="button"
            class="upload-area"
            @click="fileInput.click()"
            @dragover.prevent
            @drop.prevent="handleFiles($event.dataTransfer.files)"
          >
            <AppIcon name="upload" :size="28" />
            <strong>
              点击上传
              <span>或 拖拽文件到此处</span>
            </strong>
            <small>文件仅暂存于当前页面，尚未上传</small>
          </button>
          <ul v-if="files.length" class="file-list">
            <li v-for="(attachment, index) in files" :key="attachment.id">
              <span>
                {{ attachment.file.name }}
                <small>待上传</small>
              </span>
              <button
                type="button"
                class="text-button"
                :aria-label="`移除文件${attachment.file.name}`"
                @click="files.splice(index, 1)"
              >
                移除
              </button>
            </li>
          </ul>
          <input
            v-model="form.links"
            class="attachment-link"
            type="url"
            aria-label="附件链接"
            placeholder="或粘贴文件链接 https://"
          />
        </div>
        <label class="field">
          <span>2. 其他说明（选填）</span>
          <textarea v-model="form.explanation" rows="5" placeholder="请输入其他说明…"></textarea>
        </label>
      </div>
    </section>
    <div v-if="notice" class="form-notice" role="status">{{ notice }}</div>
    <footer class="form-actions">
      <span>请确认政策信息填写准确后提交</span>
      <button type="button" class="secondary" @click="reset">取消</button>
      <button type="submit" class="primary">
        提交申请
        <AppIcon name="arrow" :size="15" />
      </button>
    </footer>
  </form>
</template>
